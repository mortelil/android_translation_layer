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
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Object lock = new Object();
	private final LinkedHashMap<String, FakeProcess> processes = new LinkedHashMap<>();
	private final HashMap<String, Integer> spawnGenerations = new HashMap<>();
	private ProcessDeathListener deathListener;
	private ChildEventListener childListener;
	/** Set from env ATL_FAKE_BACKEND_FAULT=spawn: the next spawn call fails (DESIGN-BRIEF 5.6). */
	private boolean spawnFaultArmed;

	public ATLFakeProcessBackend(ATLLoadedApp app) {
		this.app = app;
		this.spawnFaultArmed = "spawn".equals(System.getenv("ATL_FAKE_BACKEND_FAULT"));
		instance = this;
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
		main.post(new Runnable() {
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
		main.post(new Runnable() {
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
		main.post(new Runnable() {
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
		main.post(new Runnable() {
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
		main.post(new Runnable() {
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
		main.post(new Runnable() {
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
	 * The process for the next op, spawning it if needed. A spawn while the
	 * fault seam is armed consumes the fault and reports {@code onHostUnavailable}
	 * instead: the host "failed to spawn" (DESIGN-BRIEF 5.6).
	 */
	private FakeProcess acquireProcess(String processName, Callback<?> callback) {
		boolean failed = false;
		synchronized (lock) {
			FakeProcess process = processes.get(processName);
			if (process != null)
				return process;
			if (spawnFaultArmed) {
				spawnFaultArmed = false;
				failed = true;
			} else {
				Integer last = spawnGenerations.get(processName);
				int generation = (last == null ? 0 : last) + 1;
				spawnGenerations.put(processName, generation);
				process = new FakeProcess(processName, generation);
				processes.put(processName, process);
				Slog.i(TAG, "spawned process " + processName + " (generation " + generation + ")");
				return process;
			}
		}
		// Outside the lock: the callback re-enters the controller.
		Slog.w(TAG, "spawn fault armed: failing spawn of " + processName);
		if (callback != null)
			callback.onHostUnavailable();
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
