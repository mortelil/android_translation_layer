/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * The service child's half of child-to-host service-start/bind forwarding.
 *
 * AOSP resolves a startService()/bindService() request to the DECLARED process of
 * the component in the single ActivityManagerService record for the application:
 * ActiveServices.startServiceLocked/bringUpServiceLocked take
 * {@code procName = r.processName} and then
 * {@code mAm.getProcessRecordLocked(procName, uid)} (pinned 94b4c163b7df
 * services/core/java/com/android/server/am/ActiveServices.java:6228-6266);
 * bindServiceInstanceLocked reaches bringUpServiceLocked the same way and the
 * client observes the outcome through
 * ApplicationThread.scheduleBindService -> ServiceDispatcher.doConnected
 * (LoadedApk.java 2402-2459). ATL's loader plays that role, so a service child
 * (a spawned process that itself hosts services) forwards the request over the
 * control link and the loader performs it through its own
 * ATLServiceController, which owns the process backend. See ATLProcessControl
 * for the wire.
 *
 * The child-side contract is kept exact: this backend is a normal
 * ATLProcessBackend, so the child's ATLServiceController keeps its own record,
 * binding identities and dispatcher; only the instance lives elsewhere. A
 * binder comes back through SVC_RESULT and is delivered through the child's own
 * ATLServiceDispatcher as onServiceConnected(), a null binder as
 * onNullBinding(), and an unreachable host as onServiceDisconnected()
 * (DO_SPAWN_FAILURE), never as a fabricated connect. Requests are asynchronous
 * from the child's point of view: the controller's call methods are called on
 * the main looper and return after the loader accepted the request; the result
 * arrives on the transport worker and is posted to the main looper, where every
 * ATLProcessBackend callback belongs.
 */
package android.atl;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ATLProcessBackend} of a service child: every op is forwarded to the
 * loader over the child's control link ({@link ATLProcessControl}, codes
 * SVC_*), and the loader's outcome is handed back to the caller as the normal
 * backend callback.
 *
 * <p>Only one instance exists per process; {@link ATLServiceController} installs
 * it when the {@code ATL_SERVICE_HOST} marker is present. Process deaths and
 * hosted instances are never reported here: a child hosts nothing of its own
 * for a forwarded component, and the loader is the authority for the declared
 * process's life.
 */
public final class ATLForwardingProcessBackend implements ATLProcessBackend {
	private static final String TAG = "ATLChildBackend";

	private static final class Holder {
		static final ATLForwardingProcessBackend INSTANCE = new ATLForwardingProcessBackend();
	}

	/** The one backend of this process; installed by ATLServiceController in a service child. */
	public static ATLForwardingProcessBackend get() {
		return Holder.INSTANCE;
	}

	/** Guards every field below. */
	private final Object lock = new Object();
	/** Per-connection request tokens; monotonic, never reused. */
	private final AtomicInteger nextToken = new AtomicInteger(1);
	/** Requests whose SVC_RESULT has not arrived yet, by token. */
	private final HashMap<Integer, Pending> pending = new HashMap<>();
	/**
	 * The loader-side bindings this child holds, by token: the child's registry
	 * keys a binding by component + {@code Intent.filterEquals}, so the same key
	 * finds the host binding an unbind has to release.
	 */
	private final ArrayList<HostBinding> bindings = new ArrayList<>();
	/** Created on first use: this backend is constructed during Context's static initialization. */
	private Handler main;

	private ATLForwardingProcessBackend() {}

	@Override
	public void setProcessDeathListener(ProcessDeathListener listener) {
		// A child never hosts a forwarded component: the loader reports the death
		// of the declared process through its own controller.
	}

	@Override
	public void setChildEventListener(ChildEventListener listener) {
		// Same as above: onStopSelf/onInstanceHosted belong to the loader's backend.
	}

	@Override
	public void startService(String processName, String componentClass, Intent intent, int startId,
	                         Callback<Integer> callback) {
		startService(processName, componentClass, intent, 0, startId, callback);
	}

	@Override
	public void startService(String processName, String componentClass, Intent intent, int flags, int startId,
	                         Callback<Integer> callback) {
		int token = nextToken.getAndIncrement();
		if (callback != null) {
			final Callback<Integer> cb = callback;
			register(token, new Pending() {
				Runnable deliver(int status, int result, IBinder binder) {
					return status == ATLProcessControl.SVC_STATUS_OK
					    ? resultTask(cb, Integer.valueOf(result)) : unavailableTask(cb);
				}
			});
		}
		if (!send(ATLProcessControl.SVC_START, token, processName, componentClass, intent, flags, startId))
			failLocally(token);
	}

	@Override
	public void bindService(String processName, String componentClass, Intent intent, Callback<IBinder> callback) {
		int token = nextToken.getAndIncrement();
		remember(token, componentClass, intent);
		if (callback != null) {
			final Callback<IBinder> cb = callback;
			register(token, new Pending() {
				Runnable deliver(int status, int result, IBinder binder) {
					// A null binder under OK is a successful null-binding, not an error
					// (AOSP reports it as onNullBinding through the dispatcher).
					return status == ATLProcessControl.SVC_STATUS_OK
					    ? resultTask(cb, binder) : unavailableTask(cb);
				}
			});
		}
		if (!send(ATLProcessControl.SVC_BIND, token, processName, componentClass, intent, 0, 0)) {
			// The loader never saw the bind: release the forwarded identity as
			// rebindService() does, so a later unbind of the same key is not
			// forwarded as a release of a binding that never existed.
			unremember(token);
			failLocally(token);
		}
	}

	@Override
	public void rebindService(String processName, String componentClass, Intent intent) {
		// The child already holds the binder for this identity; the loader only has
		// to run onRebind() on the surviving instance of the declared process.
		int token = nextToken.getAndIncrement();
		forgetComponent(componentClass, intent);
		remember(token, componentClass, intent);
		if (!send(ATLProcessControl.SVC_REBIND, token, processName, componentClass, intent, 0, 0))
			unremember(token);
	}

	@Override
	public void unbindService(String processName, String componentClass, Intent intent, Callback<Boolean> callback) {
		int token = tokenFor(componentClass, intent);
		if (token == 0) {
			// No binding was forwarded for this identity (the loader never accepted
			// the bind, or the identity was already released): nothing to unbind.
			Slog.w(TAG, "unbind of " + componentClass + " has no forwarded binding");
			if (callback != null)
				post(resultTask(callback, Boolean.FALSE));
			return;
		}
		unremember(token);
		if (callback != null) {
			final Callback<Boolean> cb = callback;
			register(token, new Pending() {
				Runnable deliver(int status, int result, IBinder binder) {
					// onUnbind()'s return value arms onRebind() in the binding process;
					// the loader performs that arming on its own record too.
					return status == ATLProcessControl.SVC_STATUS_OK
					    ? resultTask(cb, Boolean.valueOf(result != 0)) : unavailableTask(cb);
				}
			});
		}
		if (!send(ATLProcessControl.SVC_UNBIND, token, processName, componentClass, intent, 0, 0))
			failLocally(token);
	}

	@Override
	public void destroyService(String processName, String componentClass) {
		int token = nextToken.getAndIncrement();
		forgetComponent(componentClass, null);
		if (!send(ATLProcessControl.SVC_DESTROY, token, processName, componentClass, null, 0, 0))
			Slog.w(TAG, "forwarding DESTROY of " + componentClass + " failed");
	}

	/**
	 * Whether the named manifest process is up. AOSP's caller (ContextImpl's
	 * non-auto-create stream check) reads this synchronously, so the answer is
	 * taken from the loader in one PROCESS_UP transaction; the loader answers on
	 * its transport worker from thread-safe state.
	 */
	@Override
	public boolean isProcessUp(String processName) {
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			data.writeString(processName);
			if (!ChildHost.transactParent(ATLProcessControl.PROCESS_UP, data, reply))
				return false;
			reply.readException();
			return reply.readInt() != 0;
		} catch (RuntimeException e) {
			Slog.w(TAG, "PROCESS_UP of " + processName + " failed", e);
			return false;
		} finally {
			data.recycle();
			reply.recycle();
		}
	}

	/* --------------------------------------------------------- result handling */

	/**
	 * SVC_RESULT from the loader, on the child's transport worker. The request
	 * Parcel is read whole before any state is touched, so a malformed result
	 * cannot leave a partially-consumed parcel or a stale pending entry.
	 */
	static boolean dispatchResult(Parcel data, Parcel reply) {
		int token = data.readInt();
		int status = data.readInt();
		int result = data.readInt();
		IBinder binder = data.readStrongBinder();
		get().complete(token, status, result, binder);
		reply.writeNoException();
		return true;
	}

	private void complete(int token, int status, int result, IBinder binder) {
		Pending op;
		synchronized (lock) {
			op = pending.remove(Integer.valueOf(token));
		}
		if (op == null) {
			// A late result (the requester already gave up, or the op needed none,
			// e.g. a rebind): dropping it is correct, but never silent.
			Slog.w(TAG, "service result " + status + " for token " + token + " has no waiting request");
			return;
		}
		post(op.deliver(status, result, binder));
	}

	/** The request could not be delivered to the loader: report it exactly like a spawn failure. */
	private void failLocally(int token) {
		Pending op;
		synchronized (lock) {
			op = pending.remove(Integer.valueOf(token));
		}
		if (op != null)
			post(op.deliver(ATLProcessControl.SVC_STATUS_HOST_UNAVAILABLE, 0, null));
	}

	/* ------------------------------------------------------------ wire helpers */

	/**
	 * Encodes and sends one SVC_* request. The reply only carries the transport
	 * exception state: the result comes back as SVC_RESULT. False when the link
	 * is gone, the transaction failed, or the loader rejected the message.
	 */
	private boolean send(int code, int token, String processName, String componentClass, Intent intent,
	                     int flags, int startId) {
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			data.writeInt(token);
			data.writeString(processName);
			ATLProcessControl.writeIntent(data, intent,
			    new ComponentName(ATLLoadedApp.getPrimaryApplication().pkg.packageName, componentClass));
			if (code == ATLProcessControl.SVC_START || code == ATLProcessControl.SVC_BIND)
				data.writeInt(flags);
			if (code == ATLProcessControl.SVC_START)
				data.writeInt(startId);
			if (!ChildHost.transactParent(code, data, reply))
				return false;
			reply.readException();
			return true;
		} catch (RuntimeException e) {
			Slog.e(TAG, "forwarding op " + code + " for " + componentClass + " to the loader failed", e);
			return false;
		} finally {
			data.recycle();
			reply.recycle();
		}
	}

	private void register(int token, Pending op) {
		synchronized (lock) {
			pending.put(Integer.valueOf(token), op);
		}
	}

	/** One forwarded binding, keyed by token for the unbind that releases it. */
	private static final class HostBinding {
		final int token;
		final String componentClass;
		final Intent intent;

		HostBinding(int token, String componentClass, Intent intent) {
			this.token = token;
			this.componentClass = componentClass;
			this.intent = intent == null ? null : new Intent(intent);
		}

		boolean matches(String componentClass, Intent intent) {
			if (!this.componentClass.equals(componentClass))
				return false;
			if (this.intent == null || intent == null)
				return this.intent == intent;
			return this.intent.filterEquals(intent);
		}
	}

	private void remember(int token, String componentClass, Intent intent) {
		synchronized (lock) {
			bindings.add(new HostBinding(token, componentClass, intent));
		}
	}

	private void unremember(int token) {
		synchronized (lock) {
			for (int i = bindings.size() - 1; i >= 0; i--)
				if (bindings.get(i).token == token)
					bindings.remove(i);
		}
	}

	/** The token of the forwarded binding of this identity, or 0. */
	private int tokenFor(String componentClass, Intent intent) {
		synchronized (lock) {
			for (int i = bindings.size() - 1; i >= 0; i--) {
				HostBinding binding = bindings.get(i);
				if (binding.matches(componentClass, intent))
					return binding.token;
			}
		}
		return 0;
	}

	/** Drops every forwarded binding of {@code componentClass} (all identities when {@code intent} is null). */
	private void forgetComponent(String componentClass, Intent intent) {
		synchronized (lock) {
			for (int i = bindings.size() - 1; i >= 0; i--) {
				HostBinding binding = bindings.get(i);
				if (binding.componentClass.equals(componentClass)
				    && (intent == null || binding.matches(componentClass, intent)))
					bindings.remove(i);
			}
		}
	}

	private void post(Runnable task) {
		main().post(task);
	}

	private Handler main() {
		synchronized (lock) {
			if (main == null)
				main = new Handler(Looper.getMainLooper());
			return main;
		}
	}

	/** One completed backend callback, delivered on the main looper. */
	private abstract static class Pending {
		/** Returns the main-looper work that reports this outcome to the caller. */
		abstract Runnable deliver(int status, int result, IBinder binder);
	}

	private static <T> Runnable resultTask(final Callback<T> callback, final T value) {
		return new Runnable() {
			public void run() {
				callback.onResult(value);
			}
		};
	}

	private static Runnable unavailableTask(final Callback<?> callback) {
		return new Runnable() {
			public void run() {
				callback.onHostUnavailable();
			}
		};
	}
}
