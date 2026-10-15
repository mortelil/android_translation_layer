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
import android.content.Intent;
import android.content.pm.PackageParser;
import android.os.ATLBinderConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Slog;
import java.io.IOException;
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
 */
public class ATLProcessHost implements ATLProcessBackend {
	private static final String TAG = "ATLProcessHost";
	private static final long DEFAULT_READY_TIMEOUT_MS = 120000L;
	private static final String READY_TIMEOUT_ENV = "ATL_SERVICE_PROCESS_READY_TIMEOUT_MS";
	/** Test-only fake split set (colon list); production never sets it. */
	private static final String EXTRA_APKS_ENV = "ATL_PROBE_EXTRA_APKS";

	private static final class Holder {
		static final ATLProcessHost INSTANCE = new ATLProcessHost();
	}

	public static ATLProcessHost get() {
		return Holder.INSTANCE;
	}

	/** Holds the launcher application while a child is live; implemented in main.c. */
	private static native void nativeHoldApp();

	/** Releases one hold taken by {@link #nativeHoldApp}; implemented in main.c. */
	private static native void nativeReleaseApp();

	/**
	 * Sets this loader process' explicit service-host marker
	 * ({@code ATL_SERVICE_HOST}). Called once per process, from the constructor
	 * below, before any spawn: the marker is part of the environment every child
	 * of this host inherits, so it reaches the child without changing the frozen
	 * {@link ATLBinderConnection#spawn} signature or environment. Only the
	 * windowless service-child boot in main.c reads it; implemented in
	 * src/api-impl-jni/android_atl_ATLProcessHost.c.
	 */
	private static native void nativeMarkServiceHost();

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

	private ATLProcessHost() {
		/*
		 * Explicit child-boot gate (architecture.md §8): a child started from this host
		 * takes main.c's windowless service-child boot. A bare transport spawn from a
		 * process that never ran this host keeps the windowed PR31 child, because
		 * spawn() only adds ATL_SERVICE_FD to the environment it was handed and this
		 * marker is not in it then.
		 */
		nativeMarkServiceHost();
	}

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
		Parcel data = encodeIntent(processName, componentClass, intent);
		submit(processName, new Op<IBinder>(true, callback, data) {
			@Override
			IBinder call(Entry e) {
				boolean added = e.addHosted(componentClass);
				try {
					Parcel reply = transactChild(e, ATLProcessControl.BIND, data);
					try {
						reply.readInt();
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
		requireHostable(processName, componentClass);
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
	 */
	private void requireHostable(String processName, String componentClass) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
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
		requireHostable(processName, componentClass);
		Parcel data = Parcel.obtain();
		try {
			ATLProcessControl.writeIntent(data, intent,
			    new ComponentName(ATLLoadedApp.getPrimaryApplication().pkg.packageName, componentClass));
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

		Op(boolean maySpawn, Callback<T> callback, Parcel data) {
			super(maySpawn, data);
			this.callback = callback;
		}

		abstract T call(Entry e);

		@Override
		void run(Entry e) {
			final T value = call(e);
			if (callback == null)
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
				String apk = app.pkg.applicationInfo.sourceDir;
				if (apk == null)
					throw new IOException("the primary application has no APK path");
				spawned = ATLBinderConnection.spawn(apk, name, Build.VERSION.SDK_INT, app.pkg.packageName,
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
		public void onStopSelf(final String componentClass, final int startId) {
			post(new Runnable() {
				public void run() {
					synchronized (lock) {
						if (entries.get(processName) != Entry.this) {
							Slog.w(TAG, "dropping stopSelf of " + componentClass + " from a stale process " + processName);
							return;
						}
					}
					ChildEventListener listener = childEventListener;
					if (listener != null)
						listener.onStopSelf(processName, componentClass, startId);
				}
			});
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
