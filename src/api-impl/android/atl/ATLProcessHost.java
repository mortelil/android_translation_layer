/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Reused sources (attribution):
 *  - Firefox a9ebfe2, src/api-impl/android/atl/ATLServiceProcessManager.java:
 *    submit()/Op (blocking work off the main looper, results posted to main),
 *    childFor()/spawn() (spawn on first use, bounded ready wait, READY timeout
 *    from ATL_SERVICE_PROCESS_READY_TIMEOUT_MS via readyTimeoutMs()),
 *    remember()/unremember()/release() (a process is released with its last
 *    component), forget() (drop by identity unless a newer process took the
 *    name), and the fault seam ATL_SERVICE_PROCESS_FAULT=spawn.
 *  - PR #31 baa9b489, src/api-impl/android/atl/ATLRemoteServices.java:
 *    process() spawn/ATLBinderConnection lifecycle (spawn + reap), remoteProcess()
 *    manifest refusals (unknown/isolated/foreign-process services).
 *
 * New in this file: per-process worker threads, per-process generations carried
 * by death events, the reaper thread, teardown that cannot double-report, the
 * hold/release of the launcher application per live child, the split-APK
 * refusal (with the ATL_PROBE_EXTRA_APKS test seam) and the STATUS diagnostic.
 * Wire contract: {@link ATLProcessControl}.
 */
package android.atl;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageParser;
import android.os.ATLBinderConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Slog;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link ATLProcessBackend} that hosts service instances in child processes of
 * the loaded package, one child (and one {@link ATLBinderConnection}) per
 * manifest process name. It hosts mechanism only: the service controller owns
 * every policy decision.
 *
 * <p>Each process name has one daemon worker thread ("ATL-process-&lt;name&gt;").
 * All blocking work for the name - spawn, child transactions, teardown - runs
 * there, serially, so at most one spawn per name is in flight and operations
 * arriving during a spawn run after it. Every {@link Callback} and listener
 * delivery is posted to the main looper, never made from the caller's stack.
 * The one exception is {@code ChildEventListener#onStopSelf}: it answers a
 * child that is parked in its own {@code Service.stopSelfResult(int)} call, so
 * it runs on the receiving worker thread and returns the decision.
 */
public class ATLProcessHost implements ATLProcessBackend {
	private static final String TAG = "ATLProcessHost";
	private static final long DEFAULT_READY_TIMEOUT_MS = 120000L;
	private static final String READY_TIMEOUT_ENV = "ATL_SERVICE_PROCESS_READY_TIMEOUT_MS";
	/** Test-only fake split set (colon list); production never sets it. */
	private static final String EXTRA_APKS_ENV = "ATL_PROBE_EXTRA_APKS";

	private static final java.util.IdentityHashMap<ATLLoadedApp, ATLProcessHost> hosts = new java.util.IdentityHashMap<>();
	private final ATLLoadedApp owner;

	private ATLProcessHost(ATLLoadedApp owner) { this.owner = owner; }

	public static synchronized ATLProcessHost get(ATLLoadedApp owner) {
		if (owner == null) throw new IllegalArgumentException("Missing service owner");
		ATLProcessHost host = hosts.get(owner);
		if (host == null) { host = new ATLProcessHost(owner); hosts.put(owner, host); }
		return host;
	}

	public static ATLProcessHost get() { return get(ATLLoadedApp.getPrimaryApplication()); }

	private String spawnApk(String processName) {
		// A process name is not a package name. The controller supplies the owner.
		String path = owner.pkg.applicationInfo.sourceDir;
		if (path == null) {
			File installed = new File(ATLPaths.installed_apks_dir, owner.pkg.packageName + ".apk");
			if (installed.isFile()) path = installed.getPath();
		}
		return path;
	}

	/** Holds the launcher application while a child is live; implemented in main.c. */
	private static native void nativeHoldApp();

	/** Releases one hold taken by {@link #nativeHoldApp}; implemented in main.c. */
	private static native void nativeReleaseApp();

	/**
	 * This host's spawn: {@link ATLBinderConnection#spawn} plus the per-call
	 * {@code ATL_SERVICE_HOST=1} marker in the CHILD's environment, which is what
	 * main.c's child-boot predicate ({@code ATL_SERVICE_FD && ATL_SERVICE_HOST})
	 * requires for the windowless service-child boot (architecture.md §8). The
	 * marker is a property of that child's environment only - this process' own
	 * environment is never written, so a bare transport spawn from this same
	 * process keeps the windowed launcher child. Both entry points share one C
	 * body (src/api-impl-jni/atl_binder_spawn.h); implemented in
	 * src/api-impl-jni/android_atl_ATLProcessHost.c.
	 */
	static native int[] nativeSpawnServiceChild(String apk, String process, int sdk,
	                                                    String parentPackage, String parentProcess)
			throws IOException;

	/** The child link broke (transport failure); the child is presumed dying. */
	private static final class LinkFailure extends RuntimeException {
		LinkFailure(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** The child answered with an exception: the link is fine, the call was refused or failed. */
	private static final class RemoteRefusal extends RuntimeException {
		RemoteRefusal(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * Posts host work to the main looper; created on first use, never in a field
	 * initializer: the controller installs this host from its constructor, and that
	 * constructor runs during android.content.Context's static initialization
	 * (Context.<clinit> -> ATLLoadedApp.getPrimaryApplication()) from JNI_OnLoad,
	 * before the launcher has prepared the main looper -- a Handler built here
	 * would dereference a null looper and fail Context's class initialization
	 * (same fix as ATLServiceController/ATLFakeProcessBackend, 5bfdbbdb).
	 */
	private Handler main;

	private Handler main() {
		synchronized (this) {
			if (main == null)
				main = new Handler(Looper.getMainLooper());
			return main;
		}
	}

	/** Guards {@link #entries} and {@link #workers}; never held across a blocking call or a callback. */
	private final Object lock = new Object();
	private final HashMap<String, Entry> entries = new HashMap<>();
	private final HashMap<String, Worker> workers = new HashMap<>();
	private volatile ProcessDeathListener deathListener;
	private volatile ChildEventListener childEventListener;


	@Override
	public void setProcessDeathListener(ProcessDeathListener listener) {
		this.deathListener = listener;
	}

	@Override
	public void setChildEventListener(ChildEventListener listener) {
		this.childEventListener = listener;
	}

	@Override
	public void startService(String processName, final String componentClass, Intent intent, int startId,
	                         Callback<Integer> callback) {
		Parcel data = encodeIntent(processName, componentClass, intent);
		data.writeInt(startId);
		submit(processName, new Op<Integer>(true, callback, data) {
			@Override
			Integer call(Entry e) {
				boolean added = e.addHosted(componentClass);
				try {
					Parcel reply = transactChild(e, ATLProcessControl.START, data);
					try {
						return Integer.valueOf(reply.readInt());
					} finally {
						reply.recycle();
					}
				} catch (RemoteRefusal r) {
					unhost(e, componentClass, added);
					throw r;
				}
			}
		});
	}

	@Override
	public void bindService(String processName, final String componentClass, Intent intent,
	                        Callback<IBinder> callback) {
		// The frozen four-argument shape always created (the pre-r4 contract).
		bindService(processName, componentClass, intent, Context.BIND_AUTO_CREATE, callback);
	}

	@Override
	public void bindService(String processName, final String componentClass, Intent intent, final int flags,
	                        Callback<IBinder> callback) {
		Parcel data = encodeIntent(processName, componentClass, intent);
		data.writeInt(flags);
		// A bind without BIND_AUTO_CREATE may not create the service's process
		// (AOSP spawns only from bringUpServiceLocked under BIND_AUTO_CREATE).
		// With the process down, this op fails as a spawn failure instead.
		submit(processName, new Op<IBinder>((flags & Context.BIND_AUTO_CREATE) != 0, callback, data) {
			@Override
			IBinder call(Entry e) {
				boolean added = e.addHosted(componentClass);
				try {
					Parcel reply = transactChild(e, ATLProcessControl.BIND, data);
					try {
						int status = reply.readInt();
						if (status == 0) {
							// No instance and the bind may not create one: the child
							// refused to bring the service up. Nothing was hosted by
							// this request, so the loader-side bookkeeping it added
							// must go again and the caller stays pending (no result).
							unhost(e, componentClass, added);
							noResult = true;
							return null;
						}
						// A null binder is a successful null-binding, not an error.
						IBinder binder = reply.readStrongBinder();
						if (binder != null)
							e.addBind();
						return binder;
					} finally {
						reply.recycle();
					}
				} catch (RemoteRefusal r) {
					unhost(e, componentClass, added);
					throw r;
				}
			}
		});
	}

	@Override
	public void rebindService(String processName, String componentClass, Intent intent) {
		Parcel data = encodeIntent(processName, componentClass, intent);
		submit(processName, new Op<Void>(false, null, data) {
			@Override
			Void call(Entry e) {
				transactChild(e, ATLProcessControl.REBIND, data).recycle();
				return null;
			}
		});
	}

	@Override
	public void unbindService(String processName, String componentClass, Intent intent,
	                          Callback<Boolean> callback) {
		Parcel data = encodeIntent(processName, componentClass, intent);
		submit(processName, new Op<Boolean>(false, callback, data) {
			@Override
			Boolean call(Entry e) {
				Parcel reply = transactChild(e, ATLProcessControl.UNBIND, data);
				boolean rebind;
				try {
					rebind = reply.readInt() != 0;
				} finally {
					reply.recycle();
				}
				e.removeBind();
				maybeRelease(e);
				return Boolean.valueOf(rebind);
			}
		});
	}

	@Override
	public void destroyService(String processName, final String componentClass) {
		requireHostable(owner, processName, componentClass);
		Parcel data = Parcel.obtain();
		data.writeString(componentClass);
		submit(processName, new Op<Void>(false, null, data) {
			@Override
			Void call(Entry e) {
				try {
					transactChild(e, ATLProcessControl.DESTROY, data).recycle();
				} catch (RemoteRefusal r) {
					// The child's state for this component is gone or never existed:
					// the host's record must not outlive the controller's decision.
					Slog.w(TAG, "child " + e.processName + " refused DESTROY of " + componentClass, r);
				}
				e.removeHosted(componentClass);
				maybeRelease(e);
				return null;
			}
		});
	}

	@Override
	public boolean isProcessUp(String processName) {
		return liveEntry(processName) != null;
	}

	/**
	 * D-local diagnostic: the child's STATUS reply as {package, processName,
	 * pid, dataDir, fd names...}, or null when the process is not up. Blocks on
	 * the child; call it from a worker or probe thread, not from callbacks.
	 */
	public String[] statusProcess(String processName) {
		Entry e = liveEntry(processName);
		if (e == null)
			return null;
		Parcel data = Parcel.obtain();
		try {
			Parcel reply = transactChild(e, ATLProcessControl.STATUS, data);
			try {
				String pkg = reply.readString();
				String process = reply.readString();
				int pid = reply.readInt();
				String dataDir = reply.readString();
				String[] fds = reply.createStringArray();
				String[] result = new String[4 + (fds == null ? 0 : fds.length)];
				result[0] = pkg;
				result[1] = process;
				result[2] = Integer.toString(pid);
				result[3] = dataDir;
				if (fds != null)
					System.arraycopy(fds, 0, result, 4, fds.length);
				return result;
			} finally {
				reply.recycle();
			}
		} finally {
			data.recycle();
		}
	}

	/* ------------------------------------------------------------ refusals */

	/**
	 * Caller errors, thrown synchronously: unknown component, isolated
	 * component, a component that belongs to another process, and packages with
	 * split APKs (never hosted). Not host unavailability.
	 *
	 * <p>{@code app} is the application that owns {@code processName}
	 * ({@link ATLLoadedAppManager#getManifestAppForProcess}): a companion
	 * package's process is validated against that package's manifest, because a
	 * declared component of the companion is absent from the launcher's manifest
	 * (observed: YouTube ReVanced binding microG's
	 * {@code org.microg.gms.ads.identifier.AdvertisingIdService} declared by
	 * {@code app.revanced.android.gms} was refused, and the refusal killed the
	 * app with {@code System.exit(10)}).
	 */
	private void requireHostable(ATLLoadedApp app, String processName, String componentClass) {
		PackageParser.Service service = ATLProcessControl.findService(app, componentClass);
		if (service == null)
			throw new IllegalArgumentException("Service " + componentClass + " is not declared in the manifest");
		if (ATLProcessControl.isIsolated(service))
			throw new UnsupportedOperationException("ATL does not host isolated services: " + componentClass);
		String declared = ATLProcessControl.componentProcess(app, service);
		if (!declared.equals(processName))
			throw new IllegalArgumentException("Service " + componentClass + " belongs to process " + declared
			                                   + ", not " + processName);
		String[] splits = app.pkg.applicationInfo.splitSourceDirs;
		if (splits != null && splits.length > 0)
			throw new UnsupportedOperationException("ATL does not host services of split APK packages");
		String extra = System.getenv(EXTRA_APKS_ENV);
		if (extra != null && !extra.isEmpty())
			throw new UnsupportedOperationException("ATL does not host services of split APK packages ("
			                                        + EXTRA_APKS_ENV + " set)");
	}

	/** Refuses, then encodes the intent; both on the caller's thread so refusals are synchronous. */
	private Parcel encodeIntent(String processName, String componentClass, Intent intent) {
		ATLLoadedApp app = owner;
		requireHostable(app, processName, componentClass);
		Parcel data = Parcel.obtain();
		try {
			ATLProcessControl.writeIntent(data, intent,
			    new ComponentName(app.pkg.packageName, componentClass));
		} catch (RuntimeException e) {
			data.recycle();
			throw e;
		}
		return data;
	}

	/* ------------------------------------------------------------- workers */

	private void submit(String processName, Task task) {
		Worker worker;
		synchronized (lock) {
			worker = workers.get(processName);
			if (worker == null) {
				worker = new Worker(processName);
				workers.put(processName, worker);
			}
		}
		worker.queue.add(task);
	}

	private void post(Runnable runnable) {
		main().post(runnable);
	}

	/** One unit of blocking work for a process name. */
	private abstract class Task {
		/** False for calls that only make sense for a running process (unbind, destroy, rebind). */
		final boolean maySpawn;
		final Parcel data;

		Task(boolean maySpawn, Parcel data) {
			this.maySpawn = maySpawn;
			this.data = data;
		}

		/** Runs against the live child on the worker thread; delivers its result. */
		abstract void run(Entry e);

		/** No process could be reached for this task. */
		abstract void fail();
	}

	private abstract class Op<T> extends Task {
		private final Callback<T> callback;
		/**
		 * The op completed without a caller-visible outcome (a bind the child
		 * refused to create for): neither {@code onResult} nor
		 * {@code onHostUnavailable} may run, the request stays pending.
		 */
		protected boolean noResult;

		Op(boolean maySpawn, Callback<T> callback, Parcel data) {
			super(maySpawn, data);
			this.callback = callback;
		}

		abstract T call(Entry e);

		@Override
		void run(Entry e) {
			final T value = call(e);
			if (callback == null || noResult)
				return;
			post(new Runnable() {
				public void run() {
					callback.onResult(value);
				}
			});
		}

		@Override
		void fail() {
			if (callback == null) {
				Slog.w(TAG, "a call without callback found no reachable child process");
				return;
			}
			post(new Runnable() {
				public void run() {
					callback.onHostUnavailable();
				}
			});
		}
	}

	private final class Worker implements Runnable {
		final String name;
		final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
		/** Only touched on the worker thread. */
		private int generation;

		Worker(String name) {
			this.name = name;
			Thread thread = new Thread(this, "ATL-process-" + name);
			thread.setDaemon(true);
			thread.start();
		}

		@Override
		public void run() {
			for (;;) {
				Task task;
				try {
					task = queue.take();
				} catch (InterruptedException e) {
					return;
				}
				try {
					runTask(task);
				} catch (Throwable t) {
					Slog.e(TAG, "worker for " + name + " failed a task", t);
					task.fail();
				} finally {
					if (task.data != null)
						task.data.recycle();
				}
			}
		}

		private void runTask(Task task) {
			Entry e = liveEntry(name);
			if (e == null) {
				if (!task.maySpawn) {
					task.fail();
					return;
				}
				e = spawn();
				if (e == null) {
					task.fail();
					// Everything queued behind a failed spawn finds no process either.
					Task pending;
					while ((pending = queue.poll()) != null) {
						pending.fail();
						if (pending.data != null)
							pending.data.recycle();
					}
					return;
				}
			}
			try {
				task.run(e);
			} catch (RuntimeException x) {
				Slog.w(TAG, "call into the process " + name + " failed", x);
				task.fail();
			}
		}

		/**
		 * Spawns the child and waits for its READY transaction. Returns the ready
		 * entry, or null after releasing whatever was started.
		 */
		private Entry spawn() {
			if (ATLProcessControl.faultApplies("spawn", name)) {
				Slog.w(TAG, "test fault: spawning the process " + name + " failed");
				return null;
			}
			ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
			int[] spawned;
			try {
				String apk = spawnApk(name);
				if (apk == null)
					throw new IOException("the primary application has no APK path");
				spawned = nativeSpawnServiceChild(apk, name, Build.VERSION.SDK_INT, app.pkg.packageName,
				                                  Application.getProcessName());
			} catch (IOException | RuntimeException x) {
				Slog.e(TAG, "spawning the process " + name + " failed", x);
				return null;
			}
			final Entry e = new Entry(name, ++generation, spawned[1]);
			synchronized (lock) {
				entries.put(name, e);
			}
			ATLBinderConnection transport;
			try {
				transport = new ATLBinderConnection(spawned[0], spawned[1], new ATLProcessControl.ParentControl(e));
			} catch (RuntimeException x) {
				// The transport closed the loader's end: the child sees EOF and exits.
				Slog.e(TAG, "cannot set up the link to process " + name + " (pid " + e.pid + ")", x);
				startReaper(e);
				teardown(e, false);
				return null;
			}
			e.transport = transport;
			startReaper(e);
			// EOF before READY wakes the waiter early; the reaper does the teardown.
			transport.onDeath(new Runnable() {
				public void run() {
					e.latch.countDown();
				}
			});
			transport.start();

			long timeout = readyTimeoutMs();
			try {
				e.latch.await(timeout, TimeUnit.MILLISECONDS);
			} catch (InterruptedException x) {
				Thread.currentThread().interrupt();
			}
			if (!e.isReady()) {
				Slog.e(TAG, "the process " + name + " (pid " + e.pid + ") did not report ready within " + timeout
				       + " ms or ended before it did");
				teardown(e, false);
				return null;
			}
			if (!e.hold()) {
				Slog.e(TAG, "the process " + name + " (pid " + e.pid + ") ended right after reporting ready");
				return null;
			}
			Slog.i(TAG, "the process " + name + " (pid " + e.pid + ", generation " + e.generation + ") is up");
			return e;
		}
	}

	private static long readyTimeoutMs() {
		try {
			return Long.parseLong(System.getenv(READY_TIMEOUT_ENV));
		} catch (RuntimeException e) {
			return DEFAULT_READY_TIMEOUT_MS;
		}
	}

	/* ------------------------------------------------------- entry / teardown */

	/** One spawned child of this loader. Mutable state is guarded by the entry's monitor. */
	private final class Entry implements ATLProcessControl.ParentEntry {
		final String processName;
		final int generation;
		final int pid;
		/** Released by READY, or by the link's EOF; the worker re-checks {@link #isReady()}. */
		final CountDownLatch latch = new CountDownLatch(1);
		volatile ATLBinderConnection transport;
		private boolean ready, down, held;
		private final HashSet<String> hosted = new HashSet<>();
		private int activeBinds;
		/**
		 * Token -&gt; the loader-side client of one binding a service child of this
		 * process forwarded (SVC_BIND). Touched on the main looper; the teardown of
		 * the entry (any thread) posts a release that empties it.
		 */
		private final HashMap<Integer, ForwardedBinding> forwarded = new HashMap<>();

		Entry(String processName, int generation, int pid) {
			this.processName = processName;
			this.generation = generation;
			this.pid = pid;
		}

		@Override
		public int pid() {
			return pid;
		}

		@Override
		public String processName() {
			return processName;
		}

		@Override
		public void onReady(String firstHostedComponent) {
			synchronized (this) {
				if (down || ready)
					throw new IllegalStateException("READY for process " + processName + " in the wrong state");
				ready = true;
			}
			Slog.i(TAG, "the process " + processName + " (pid " + pid + ") reported ready, first component "
			       + firstHostedComponent);
			latch.countDown();
		}

		@Override
		public boolean onStopSelf(final String componentClass, final int startId) {
			synchronized (lock) {
				if (entries.get(processName) != Entry.this) {
					Slog.w(TAG, "dropping stopSelf of " + componentClass + " from a stale process " + processName);
					return false;
				}
			}
			// Decided on the worker that received the message, not posted: the child's
			// Service.stopSelfResult() is parked in this call until this value returns.
			// The listener decides from thread-safe state (never the main looper) and
			// returns false when it cannot authorise the stop.
			ChildEventListener listener = childEventListener;
			return listener != null && listener.onStopSelf(processName, componentClass, startId);
		}

		@Override
		public void onRejectedControlMessage(String reason) {
			/*
			 * The child sent a control message this loader rejects. Close its channel:
			 * the child sees EOF, winds down, and the reaper's teardown() reports the loss
			 * through onProcessDied() - never an exception on a loader thread, which the
			 * runtime's uncaught handler turns into a whole-loader exit (status 10).
			 */
			Slog.e(TAG, "the process " + processName + " (pid " + pid + ") sent a control message this loader"
			       + " rejects (" + reason + "); closing that child's link");
			ATLBinderConnection transport = this.transport;
			if (transport != null)
				transport.close();
		}

		@Override
		public boolean isProcessUp(String processName) {
			// Synchronous PROCESS_UP, answered on the transport worker the child is
			// parked in. AOSP's process check (ActiveServices.bringUpServiceLocked:
			// getProcessRecordLocked(procName, uid)) sees the loader's own process
			// as up, and any spawned child that already reported READY.
			if (processName.equals(Application.getProcessName()))
				return true;
			synchronized (lock) {
				Entry other = entries.get(processName);
				return other != null && other.isReady();
			}
		}

		@Override
		public void onForwardedServiceOp(final int code, final int token, final String process,
		                                 final ComponentName component, final Intent intent,
		                                 final int flags, final int startId) {
			// Accepted on the transport worker, performed on the loader's main looper
			// (where the controller and its callbacks live), answered later through
			// SVC_RESULT. The worker must stay free for the child's synchronous calls.
			post(new Runnable() {
				public void run() {
					forwardOnMain(code, token, process, component, intent, flags, startId);
				}
			});
		}

		/**
		 * One forwarded request, on the main looper. The component must be declared
		 * for exactly the process the child resolved (a child cannot talk the loader
		 * into running an arbitrary component elsewhere), and the loader's own
		 * {@link ATLLoadedApp#bindService}/{@link ATLLoadedApp#startService} then
		 * applies the same placement and backend rules as a loader-initiated call -
		 * the service still runs in its DECLARED process, never downgraded.
		 */
		private void forwardOnMain(int code, int token, String process, ComponentName component,
		                           Intent intent, int flags, int startId) {
			ATLLoadedApp app = owner;
			PackageParser.Service service = ATLProcessControl.findService(app, component.getClassName());
			if (service == null || !app.pkg.packageName.equals(component.getPackageName())
			    || !process.equals(ATLProcessControl.componentProcess(app, service))) {
				Slog.w(TAG, "refusing a forwarded service op " + code + " for " + component + " from process "
				       + processName);
				sendServiceResult(token, ATLProcessControl.SVC_STATUS_REFUSED, 0, null);
				return;
			}
			switch (code) {
			case ATLProcessControl.SVC_START: {
				ComponentName resolved;
				try {
					resolved = app.startService(intent);
				} catch (RuntimeException x) {
					Slog.w(TAG, "forwarded startService of " + component + " was refused", x);
					sendServiceResult(token, ATLProcessControl.SVC_STATUS_REFUSED, 0, null);
					return;
				}
				if (resolved == null) {
					sendServiceResult(token, ATLProcessControl.SVC_STATUS_REFUSED, 0, null);
					return;
				}
				// The loader's controller owns the instance and its restart ladder;
				// the child's mirrored record only needs a valid start result.
				sendServiceResult(token, ATLProcessControl.SVC_STATUS_OK,
				                  android.app.Service.START_STICKY_COMPATIBILITY, null);
				return;
			}
			case ATLProcessControl.SVC_BIND: {
				Context context = app.getApplication();
				ServiceConnection connection = new ForwardedConnection(token, true);
				ForwardedBinding binding = new ForwardedBinding(context, connection);
				synchronized (forwarded) {
					forwarded.put(Integer.valueOf(token), binding);
				}
				ComponentName resolved;
				try {
					// Same flags as the child's caller: a bind without BIND_AUTO_CREATE
					// keeps the loader's guard (it may not spawn a process).
					resolved = app.bindService(context, intent, connection, flags);
				} catch (RuntimeException x) {
					forgetForwarded(token);
					Slog.w(TAG, "forwarded bindService of " + component + " was refused", x);
					sendServiceResult(token, ATLProcessControl.SVC_STATUS_REFUSED, 0, null);
					return;
				}
				if (resolved == null) {
					forgetForwarded(token);
					sendServiceResult(token, ATLProcessControl.SVC_STATUS_REFUSED, 0, null);
				}
				// Otherwise the result arrives through ForwardedConnection, on the main
				// looper, exactly where an app's onServiceConnected would run.
				return;
			}
			case ATLProcessControl.SVC_REBIND: {
				// The child already holds the binder for this identity: the loader only
				// has to run onRebind() on the surviving instance. A fresh registration
				// makes the controller consume its armed rebind state.
				Context context = app.getApplication();
				ServiceConnection connection = new ForwardedConnection(token, false);
				synchronized (forwarded) {
					forwarded.put(Integer.valueOf(token), new ForwardedBinding(context, connection));
				}
				try {
					app.bindService(context, intent, connection, Context.BIND_AUTO_CREATE);
				} catch (RuntimeException x) {
					forgetForwarded(token);
					Slog.w(TAG, "forwarded rebind of " + component + " was refused", x);
				}
				return;
			}
			case ATLProcessControl.SVC_UNBIND: {
				ForwardedBinding binding = forgetForwarded(token);
				if (binding != null) {
					try {
						app.unbindService(binding.context, binding.connection);
					} catch (RuntimeException x) {
						Slog.w(TAG, "forwarded unbind of " + component + " failed", x);
					}
				}
				// The loader's controller applies onUnbind()'s rebind arming to its own
				// record; the child's record mirrors the identity but not the arming,
				// so a later bind re-runs onBind() there (never a false rebind).
				sendServiceResult(token, ATLProcessControl.SVC_STATUS_OK, 0, null);
				return;
			}
			default: { // SVC_DESTROY
				try {
					app.stopService(new Intent().setComponent(component));
				} catch (RuntimeException x) {
					Slog.w(TAG, "forwarded stopService of " + component + " failed", x);
				}
				return;
			}
			}
		}

		private ForwardedBinding forgetForwarded(int token) {
			synchronized (forwarded) {
				return forwarded.remove(Integer.valueOf(token));
			}
		}

		/**
		 * Releases every loader-side binding this process forwarded: a dead client's
		 * registrations must not keep the declared process alive (AOSP
		 * ActiveServices.removeConnectionLocked/cleanUpApplicationRecordLocked).
		 * Runs on the main looper; the entry is already down, so nothing is reported.
		 */
		private void releaseForwarded() {
			ArrayList<ForwardedBinding> released;
			synchronized (forwarded) {
				if (forwarded.isEmpty())
					return;
				released = new ArrayList<>(forwarded.values());
				forwarded.clear();
			}
			ATLLoadedApp app = owner;
			for (ForwardedBinding binding : released) {
				try {
					app.unbindService(binding.context, binding.connection);
				} catch (RuntimeException x) {
					Slog.w(TAG, "releasing a forwarded binding of " + processName + " failed", x);
				}
			}
		}

		private void releaseForwardedBinding(ForwardedBinding binding) {
			if (binding == null) return;
			try {
				owner.unbindService(binding.context, binding.connection);
			} catch (RuntimeException failure) {
				Slog.w(TAG, "cannot release forwarded binding of " + processName, failure);
			}
		}

		@Override
		public void sendServiceResult(int token, int status, int result, IBinder binder) {
			ATLBinderConnection transport = this.transport;
			if (status == ATLProcessControl.SVC_STATUS_OK && binder != null
			    && transport != null && !transport.canTransmit(binder)) {
				// The binder is a proxy of another link (a service implemented in a
				// third process): relaying it is unsupported. Report a refusal - the
				// child maps that to its documented spawn-failure path - instead of
				// letting the write fail and close this child's link.
				Slog.w(TAG, "cannot relay a foreign binder of " + processName + " to its requester");
				status = ATLProcessControl.SVC_STATUS_REFUSED;
				result = 0;
				binder = null;
				ForwardedBinding refused = forgetForwarded(token);
				releaseForwardedBinding(refused);
			}
			Parcel data = Parcel.obtain();
			try {
				data.writeInt(token);
				data.writeInt(status);
				data.writeInt(result);
				data.writeStrongBinder(binder);
				transactChild(Entry.this, ATLProcessControl.SVC_RESULT, data).recycle();
			} catch (RuntimeException x) {
				ForwardedBinding failed = forgetForwarded(token);
				releaseForwardedBinding(failed);
				Slog.w(TAG, "cannot deliver a service result to process " + processName, x);
			} finally {
				data.recycle();
			}
		}

		/** The loader-side client of one forwarded binding. Main looper only. */
		private final class ForwardedBinding {
			final Context context;
			final ServiceConnection connection;

			ForwardedBinding(Context context, ServiceConnection connection) {
				this.context = context;
				this.connection = connection;
			}
		}

		/**
		 * The loader's own client for a forwarded bind: every app-visible outcome of
		 * the loader's controller is reported back to the requesting child with the
		 * token it sent (AOSP's client observes the same events through
		 * ServiceDispatcher.doConnected, LoadedApk.java 2402-2459).
		 */
		private final class ForwardedConnection implements ServiceConnection {
			private final int token;
			/** False for a rebind: the requester already holds its binder and awaits nothing. */
			private final boolean report;
			/** Whether this client's binder was already reported to the child. */
			private boolean delivered;

			ForwardedConnection(int token, boolean report) {
				this.token = token;
				this.report = report;
			}

			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				if (!report)
					return;
				delivered = true;
				sendServiceResult(token, ATLProcessControl.SVC_STATUS_OK, 1, service);
			}

			@Override
			public void onNullBinding(ComponentName name) {
				// The binding machinery worked; onBind() returned no binder. Forwarded as
				// a successful null result: the child reports onNullBinding(). Delivery
				// of a null binder cannot die later, so it is not counted as delivered.
				if (report)
					sendServiceResult(token, ATLProcessControl.SVC_STATUS_OK, 0, null);
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				// Before a result: the approved DO_SPAWN_FAILURE mapping. After one:
				// the host of a live binding went down, AOSP's onServiceDisconnected()
				// for a client that keeps its binding - reported as a death so the
				// child forwards the disconnect instead of dropping it.
				if (!report)
					return;
				sendServiceResult(token, delivered ? ATLProcessControl.SVC_STATUS_DIED
				                                   : ATLProcessControl.SVC_STATUS_HOST_UNAVAILABLE, 0, null);
			}

			@Override
			public void onBindingDied(ComponentName name) {
				if (report)
					sendServiceResult(token, ATLProcessControl.SVC_STATUS_BINDING_DIED, 0, null);
			}
		}

		synchronized boolean isReady() {
			return ready && !down;
		}

		/** Takes the launcher hold for this live child, once; false if it is already torn down. */
		synchronized boolean hold() {
			if (down || !ready)
				return false;
			held = true;
			post(new Runnable() {
				public void run() {
					nativeHoldApp();
				}
			});
			return true;
		}

		synchronized boolean addHosted(String componentClass) {
			return hosted.add(componentClass);
		}

		synchronized void removeHosted(String componentClass) {
			hosted.remove(componentClass);
		}

		synchronized void addBind() {
			activeBinds++;
		}

		/*
		 * Clamped at zero: a null onBind() is a successful binding that is not
		 * counted, yet its unbind still arrives; hosted components remain the
		 * primary release guard.
		 */
		synchronized void removeBind() {
			if (activeBinds > 0)
				activeBinds--;
		}
	}

	/** The ready, not-torn-down child of {@code processName}, or null. */
	private Entry liveEntry(String processName) {
		Entry e;
		synchronized (lock) {
			e = entries.get(processName);
		}
		return e != null && e.isReady() ? e : null;
	}

	/** Undoes the hosted record of a refused first start/bind; releases a child left empty. */
	private void unhost(Entry e, String componentClass, boolean added) {
		if (!added)
			return;
		e.removeHosted(componentClass);
		maybeRelease(e);
	}

	/** Release rule: no hosted component and no active bind means the child has no reason to live. */
	private void maybeRelease(Entry e) {
		boolean release;
		synchronized (e) {
			release = e.ready && !e.down && e.hosted.isEmpty() && e.activeBinds == 0;
		}
		if (release) {
			Slog.i(TAG, "releasing the process " + e.processName + " (pid " + e.pid + ")");
			teardown(e, false);
		}
	}

	/**
	 * Ends an entry exactly once. The caller that flips {@code down} completes
	 * the teardown; any other caller (the reaper after a release, a release
	 * racing a crash) returns without reporting. The entry leaves the map first
	 * so stale events drop by identity, then the transport is closed (the child
	 * sees EOF and exits 0), then events are posted.
	 */
	private void teardown(Entry e, boolean childDied) {
		boolean release;
		boolean report;
		synchronized (e) {
			if (e.down)
				return;
			e.down = true;
			release = e.held;
			e.held = false;
			report = childDied && !e.hosted.isEmpty();
		}
		e.latch.countDown();
		boolean current;
		synchronized (lock) {
			current = entries.get(e.processName) == e;
			if (current)
				entries.remove(e.processName);
		}
		ATLBinderConnection transport = e.transport;
		if (transport != null)
			transport.close();
		if (report && current) {
			final String name = e.processName;
			final int generation = e.generation;
			Slog.w(TAG, "the process " + name + " (generation " + generation + ") died while hosting components");
			post(new Runnable() {
				public void run() {
					ProcessDeathListener listener = deathListener;
					if (listener != null)
						listener.onProcessDied(name, generation);
				}
			});
		}
		// A dead client's registrations must not keep a declared process alive:
		// AOSP drops them with the client (ActiveServices.killServicesLocked ->
		// removeConnectionLocked). Posted: the map belongs to the main looper.
		post(new Runnable() {
			public void run() {
				e.releaseForwarded();
			}
		});
		if (release) {
			post(new Runnable() {
				public void run() {
					nativeReleaseApp();
				}
			});
		}
	}

	/** Blocks in waitpid for the child, then closes the link and tears the entry down as a death. */
	private void startReaper(final Entry e) {
		Thread reaper = new Thread(new Runnable() {
			public void run() {
				ATLBinderConnection.reap(e.pid);
				ATLBinderConnection transport = e.transport;
				if (transport != null)
					transport.close();
				teardown(e, true);
			}
		}, "ATL-reaper-" + e.pid);
		reaper.setDaemon(true);
		reaper.start();
	}

	/**
	 * One synchronous transaction to the child's control binder (its transport
	 * root). Returns the reply with its leading exception already consumed;
	 * the caller recycles it. A broken link is a {@link LinkFailure}; an
	 * exception the child wrote is a {@link RemoteRefusal}.
	 */
	private static Parcel transactChild(Entry e, int code, Parcel data) {
		ATLBinderConnection transport = e.transport;
		if (transport == null)
			throw new LinkFailure("no link to process " + e.processName, null);
		Parcel reply = Parcel.obtain();
		boolean delivered;
		try {
			delivered = transport.remote(1).transact(code, data, reply, 0);
		} catch (RuntimeException x) {
			reply.recycle();
			throw new LinkFailure("transaction " + code + " to process " + e.processName + " failed", x);
		}
		if (!delivered) {
			reply.recycle();
			throw new LinkFailure("transaction " + code + " to process " + e.processName + " failed", null);
		}
		try {
			reply.readException();
		} catch (RuntimeException x) {
			reply.recycle();
			throw new RemoteRefusal(x);
		}
		return reply;
	}
}
