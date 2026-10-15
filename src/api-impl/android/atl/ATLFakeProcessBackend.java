/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Lane-B test fake (LIFECYCLE-FOUNDATION DESIGN-BRIEF 6): an in-loader
 * process host that stands in for the lane-D child-process transport until
 * the transport gate passes. The child-side semantics mirror
 * ATLLoadedApp/ATLServiceController.bringUpService (base ab7345cf): the
 * instance is created through the app's class loader, its fresh ContextImpl
 * is attached and the instance published BEFORE onCreate() runs, so a
 * re-entrant call sees it.
 *
 * This host is deliberately asynchronous in the same way a real child is:
 * every operation is posted to the main looper and its result callback is
 * invoked inside that posted op (never synchronously from the controller
 * call that triggered it, per the ATLProcessBackend contract). Killing a
 * process through the test seam fires the same ProcessDeathListener
 * notification a real child death would, so the controller's death path is
 * the production one; a test NEVER invokes client callbacks directly.
 */
package android.atl;

import android.app.ContextImpl;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * {@link ATLProcessBackend} implementation hosting every "remote" service in
 * the loader's own process, one {@link FakeProcess} per process name.
 * Instances are created through {@link ATLLoadedApp#loadClass}; a process
 * exists while it hosts at least one live instance and is dropped when the
 * last one is destroyed (destroyService) or killed (killProcess).
 */
public final class ATLFakeProcessBackend implements ATLProcessBackend {
	private static final String TAG = "ATLFakeProcessBackend";

	/** Test handle: the backend of the loaded app (one per loader process). */
	private static ATLFakeProcessBackend instance;

	/** The test seam: {@link #killProcess} fires the real death path. */
	public static ATLFakeProcessBackend instance() {
		return instance;
	}

	/** One hosted service instance with its bind identities (one onBind per filterEquals identity). */
	private static final class HostedService {
		final Service instance;
		final ArrayList<Intent> identities = new ArrayList<>();
		final ArrayList<IBinder> binders = new ArrayList<>();

		HostedService(Service instance) {
			this.instance = instance;
		}

		int identityOf(Intent intent) {
			for (int i = 0; i < identities.size(); i++) {
				Intent known = identities.get(i);
				if (known == intent || (known != null && intent != null && known.filterEquals(intent)))
					return i;
			}
			return -1;
		}
	}

	/** One hosted process: its name, its spawn generation, its live instances. */
	private static final class FakeProcess {
		final String name;
		final int generation;
		final LinkedHashMap<String, HostedService> services = new LinkedHashMap<>();

		FakeProcess(String name, int generation) {
			this.name = name;
			this.generation = generation;
		}
	}

	private final ATLLoadedApp app;
	/**
	 * Posts backend callbacks to the main looper; created on first use because the
	 * backend is built from ATLServiceController's constructor, which runs during
	 * android.content.Context's static initialization, before the main looper exists.
	 */
	private Handler main;

	private Handler main() {
		synchronized (this) {
			if (main == null)
				main = new Handler(Looper.getMainLooper());
			return main;
		}
	}
	private final Object lock = new Object();
	private final LinkedHashMap<String, FakeProcess> processes = new LinkedHashMap<>();
	private final HashMap<String, Integer> spawnGenerations = new HashMap<>();
	private ProcessDeathListener deathListener;
	private ChildEventListener childListener;
	/** The one-shot spawn fault armed by {@link #armProcessFault}, or null; guarded by {@link #lock}. */
	private ProcessFault processFault;

	public ATLFakeProcessBackend(ATLLoadedApp app) {
		this.app = app;
		// A malformed spec is a caller error: refuse it loudly rather than run a row
		// that believes it armed a fault it never armed.
		armProcessFault(System.getenv(ENV_PROCESS_FAULT));
		instance = this;
	}

	// ---------------------------------------------------------------------
	// the spawn-fault seam (frozen spec: arm[:process], one-shot)
	// ---------------------------------------------------------------------

	/** Frozen fault-spec env: {@code spawn|crash-before-ready[:proc]|stall-before-ready[:proc]}. */
	static final String ENV_PROCESS_FAULT = "ATL_SERVICE_PROCESS_FAULT";

	/** The host never starts for this op: the op is answered with {@code onHostUnavailable}. */
	private static final int FAULT_SPAWN = 1;
	/** The child dies between exec and its first transaction: no op answer, a real death notice. */
	private static final int FAULT_CRASH_BEFORE_READY = 2;
	/** The child lives but never reports ready: the op is never answered. */
	private static final int FAULT_STALL_BEFORE_READY = 3;

	/** One parsed arm and the process it is bound to ({@code null} = the next spawn of any process). */
	private static final class ProcessFault {
		final int arm;
		final String process;

		ProcessFault(int arm, String process) {
			this.arm = arm;
			this.process = process;
		}

		boolean matches(String processName) {
			return process == null || process.equals(processName);
		}
	}

	/**
	 * Parses the frozen spec, splitting at the FIRST {@code ':'} into the arm and
	 * the optional process it is bound to. Returns null for "no fault armed". An
	 * unknown arm is refused loudly: silently arming nothing would let a row pass
	 * or fail for a reason it never exercised.
	 */
	private static ProcessFault parseProcessFault(String spec) {
		if (spec == null)
			return null;
		String text = spec.trim();
		if (text.isEmpty())
			return null;
		int at = text.indexOf(':');
		String arm = at < 0 ? text : text.substring(0, at);
		String process = at < 0 ? null : text.substring(at + 1);
		if (process != null && process.isEmpty())
			process = null;
		if ("spawn".equals(arm))
			return new ProcessFault(FAULT_SPAWN, process);
		if ("crash-before-ready".equals(arm))
			return new ProcessFault(FAULT_CRASH_BEFORE_READY, process);
		if ("stall-before-ready".equals(arm))
			return new ProcessFault(FAULT_STALL_BEFORE_READY, process);
		throw new IllegalArgumentException("unknown " + ENV_PROCESS_FAULT + " [" + spec
		    + "]: expected spawn|crash-before-ready[:proc]|stall-before-ready[:proc]");
	}

	/**
	 * Arms one fault through the same parser as {@link #ENV_PROCESS_FAULT}, so a
	 * probe can arm {@code crash-before-ready} or {@code stall-before-ready} at
	 * runtime. The arm is one-shot and is consumed by the next spawn of a process
	 * it matches; an arm naming a process never fires for another process.
	 */
	public void armProcessFault(String spec) {
		ProcessFault fault = parseProcessFault(spec);
		synchronized (lock) {
			processFault = fault;
		}
	}

	@Override
	public void setProcessDeathListener(ProcessDeathListener listener) {
		deathListener = listener;
	}

	@Override
	public void setChildEventListener(ChildEventListener listener) {
		childListener = listener;
	}

	/**
	 * TEST SEAM (DESIGN-BRIEF 6): kills the named process for real. The process
	 * and every hosted instance are dropped at once and the death is reported
	 * through {@link ProcessDeathListener#onProcessDied} on the main looper --
	 * the production death path. Killed instances do NOT get
	 * {@link ChildEventListener#onInstanceReleased}: the controller's death
	 * handling clears its own hosted-instance state first.
	 */
	public void killProcess(final String processName) {
		final FakeProcess process;
		synchronized (lock) {
			process = processes.remove(processName);
		}
		if (process == null)
			return;
		Slog.i(TAG, "killProcess: " + processName + " (generation " + process.generation + ")");
		main().post(new Runnable() {
			@Override
			public void run() {
				if (deathListener != null)
					deathListener.onProcessDied(processName, process.generation);
			}
		});
	}

	@Override
	public void startService(final String processName, final String componentClass, final Intent intent,
	                         final int startId, final Callback<Integer> callback) {
		startService(processName, componentClass, intent, 0, startId, callback);
	}

	@Override
	public void startService(final String processName, final String componentClass, final Intent intent,
	                         final int flags, final int startId, final Callback<Integer> callback) {
		main().post(new Runnable() {
			@Override
			public void run() {
				FakeProcess process = acquireProcess(processName, callback);
				if (process == null)
					return;
				try {
					HostedService hosted = acquireService(process, componentClass);
					int res = hosted.instance.onStartCommand(intent, flags, startId);
					if (callback != null)
						callback.onResult(res);
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "startService: cannot instantiate " + componentClass, e);
					if (callback != null)
						callback.onHostUnavailable();
				}
			}
		});
	}

	@Override
	public void bindService(final String processName, final String componentClass, final Intent intent,
	                        final Callback<IBinder> callback) {
		main().post(new Runnable() {
			@Override
			public void run() {
				FakeProcess process = acquireProcess(processName, callback);
				if (process == null)
					return;
				try {
					HostedService hosted = acquireService(process, componentClass);
					// One onBind() per binding identity per instance, like the
					// local path's ensureBindingIdentity.
					int at = hosted.identityOf(intent);
					if (at >= 0) {
						if (callback != null)
							callback.onResult(hosted.binders.get(at));
						return;
					}
					IBinder binder = hosted.instance.onBind(intent);
					hosted.identities.add(intent);
					hosted.binders.add(binder);
					if (callback != null)
						callback.onResult(binder);
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "bindService: cannot instantiate " + componentClass, e);
					if (callback != null)
						callback.onHostUnavailable();
				}
			}
		});
	}

	@Override
	public void rebindService(final String processName, final String componentClass, final Intent intent) {
		main().post(new Runnable() {
			@Override
			public void run() {
				HostedService hosted = findService(processName, componentClass);
				if (hosted != null)
					hosted.instance.onRebind(intent);
			}
		});
	}

	@Override
	public void unbindService(final String processName, final String componentClass, final Intent intent,
	                          final Callback<Boolean> callback) {
		main().post(new Runnable() {
			@Override
			public void run() {
				FakeProcess process;
				synchronized (lock) {
					process = processes.get(processName);
				}
				if (process == null) {
					// The hosting process is gone: its state was cleared by the
					// death path; there is nothing to unbind.
					if (callback != null)
						callback.onHostUnavailable();
					return;
				}
				HostedService hosted = process.services.get(componentClass);
				if (hosted == null) {
					if (callback != null)
						callback.onHostUnavailable();
					return;
				}
				int at = hosted.identityOf(intent);
				if (at >= 0) {
					hosted.identities.remove(at);
					hosted.binders.remove(at);
				}
				boolean res = hosted.instance.onUnbind(intent);
				if (callback != null)
					callback.onResult(res);
			}
		});
	}

	@Override
	public void destroyService(final String processName, final String componentClass) {
		main().post(new Runnable() {
			@Override
			public void run() {
				FakeProcess process;
				HostedService hosted;
				synchronized (lock) {
					process = processes.get(processName);
					hosted = process == null ? null : process.services.remove(componentClass);
				}
				if (hosted == null) {
					dropProcessIfEmpty(processName);
					return;
				}
				hosted.instance.onDestroy();
				if (childListener != null)
					childListener.onInstanceReleased(processName, componentClass, hosted.instance);
				dropProcessIfEmpty(processName);
			}
		});
	}

	@Override
	public boolean isProcessUp(String processName) {
		synchronized (lock) {
			return processes.containsKey(processName);
		}
	}

	/**
	 * The process for the next op, spawning it if needed. A spawn that consumes a
	 * one-shot fault follows the frozen arm instead of the spawn: {@code spawn}
	 * answers the op with {@code onHostUnavailable} and spawns nothing,
	 * {@code crash-before-ready[:proc]} spawns the process, drops it immediately
	 * and reports its death through {@link ProcessDeathListener#onProcessDied}
	 * without ever answering the op, and {@code stall-before-ready[:proc]} spawns
	 * the process and never answers the op.
	 */
	private FakeProcess acquireProcess(String processName, Callback<?> callback) {
		ProcessFault fault;
		final FakeProcess spawned;
		synchronized (lock) {
			FakeProcess process = processes.get(processName);
			if (process != null)
				return process;
			fault = processFault != null && processFault.matches(processName) ? processFault : null;
			if (fault != null)
				// One-shot: consumed by this spawn, whatever the arm does next.
				processFault = null;
			if (fault != null && fault.arm == FAULT_SPAWN) {
				spawned = null;
			} else {
				Integer last = spawnGenerations.get(processName);
				int generation = (last == null ? 0 : last) + 1;
				spawnGenerations.put(processName, generation);
				spawned = new FakeProcess(processName, generation);
				processes.put(processName, spawned);
				Slog.i(TAG, "spawned process " + processName + " (generation " + generation + ")");
			}
		}
		if (fault == null)
			return spawned;
		// Outside the lock: both the op callback and the death notice re-enter the controller.
		if (fault.arm == FAULT_SPAWN) {
			Slog.w(TAG, "spawn fault: failing the spawn of " + processName);
			if (callback != null)
				callback.onHostUnavailable();
			return null;
		}
		final int generation = spawned.generation;
		if (fault.arm == FAULT_STALL_BEFORE_READY) {
			Slog.w(TAG, "stall-before-ready: process " + processName + " (generation " + generation
			    + ") never reports ready; the op stays unanswered");
			return null;
		}
		// crash-before-ready: the child dies between exec and its first
		// transaction. Drop it first, then report the death - and never answer the
		// op that triggered the spawn.
		synchronized (lock) {
			processes.remove(processName);
		}
		Slog.w(TAG, "crash-before-ready: process " + processName + " (generation " + generation
		    + ") died before its first op");
		main().post(new Runnable() {
			@Override
			public void run() {
				if (deathListener != null)
					deathListener.onProcessDied(processName, generation);
			}
		});
		return null;
	}

	/** The hosted instance of {@code componentClass}, created (attach, publish, onCreate) if absent. */
	private HostedService acquireService(FakeProcess process, String componentClass) throws ReflectiveOperationException {
		synchronized (lock) {
			HostedService hosted = process.services.get(componentClass);
			if (hosted != null)
				return hosted;
		}
		Class<? extends Service> cls = app.loadClass(componentClass).asSubclass(Service.class);
		Service service = cls.getConstructor().newInstance();
		ContextImpl serviceContext = new ContextImpl(
		    app.createDefaultResources(), app, app.pkg.applicationInfo.theme);
		service.attachBaseContext(serviceContext);
		HostedService hosted = new HostedService(service);
		synchronized (lock) {
			process.services.put(componentClass, hosted);
		}
		// Report the hosted instance before any child-side lifecycle runs: a
		// stopSelf() issued re-entrantly from onCreate()/onStartCommand() must
		// already find its authoritative record.
		if (childListener != null)
			childListener.onInstanceHosted(process.name, componentClass, service);
		service.onCreate();
		return hosted;
	}

	private HostedService findService(String processName, String componentClass) {
		synchronized (lock) {
			FakeProcess process = processes.get(processName);
			return process == null ? null : process.services.get(componentClass);
		}
	}

	/** A process whose last instance is gone "exits" (DESIGN-BRIEF 6). */
	private void dropProcessIfEmpty(String processName) {
		synchronized (lock) {
			FakeProcess process = processes.get(processName);
			if (process != null && process.services.isEmpty())
				processes.remove(processName);
		}
	}
}
