/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Interface frozen per LIFECYCLE-FOUNDATION architecture §8; shape follows the
 * Firefox-lane ATLServiceProcessManager public API (references/firefox @5418fb3).
 */
package android.atl;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Child-process host for service instances.
 *
 * <p>The service controller owns all policy (start IDs, restart scheduling,
 * bound-client bookkeeping, teardown decisions); a backend only hosts the
 * instances and reports what happened. Every {@link Callback} and listener is
 * delivered on the main looper and never synchronously from within the
 * controller call that triggered it, so the controller may call any method
 * here while holding its own state.
 *
 * <p>A {@code null} result from {@link #bindService} means the service
 * returned null from {@code onBind()}, which is a successful call;
 * {@link Callback#onHostUnavailable()} means the hosting process could not
 * be spawned.
 *
 * <p>This in-loader interface maps one-to-one onto the lane-D ChildHost wire
 * transactions (frozen codes: READY=1, START=2, BIND=3, UNBIND=4, DESTROY=5,
 * REBIND=6, plus D-local STATUS=7 diagnostics); {@link #rebindService} is the
 * REBIND path. Lane D's ATLProcessHost implements this interface.
 */
public interface ATLProcessBackend {

	/**
	 * Result callback of one request. A null result from {@link #bindService}
	 * is a successful call (the service returned null from {@code onBind()}).
	 * {@link #onHostUnavailable()} is reported instead of a result when the
	 * child process could not be spawned.
	 */
	interface Callback<T> {
		void onResult(T result);

		void onHostUnavailable();

		/**
		 * The host of a binding that ALREADY produced a result went down: the
		 * binder the caller holds is dead (AOSP would deliver
		 * {@code onServiceDisconnected()}). A later {@link #onResult} for the same
		 * request is a reconnect, not a second first-connect; a backend that cannot
		 * tell the two apart must fall back to {@link #onHostUnavailable()}.
		 */
		default void onHostDied() {
			onHostUnavailable();
		}
	}

	/**
	 * Reports that a hosting process died while it hosted at least one
	 * instance. {@code generation} is the DYING child's per-process
	 * generation, assigned at spawn and monotonic from 1 per process name:
	 * the first child of a name is 1, its death reports 1, its respawn is 2.
	 * Controller callbacks captured for a generation &lt;= the reported one
	 * are stale and must be ignored.
	 */
	interface ProcessDeathListener {
		void onProcessDied(String processName, int generation);
	}

	/** Reports events that originate inside the hosting side rather than from a controller call. */
	interface ChildEventListener {
		/**
		 * Child-side stopSelf()/stopSelfResult() report; the controller decides authoritatively and
		 * the returned decision is the reply the child's {@code Service.stopSelfResult(int)} returns
		 * (false when it cannot be authorised). Called on a transport worker thread the child is
		 * parked in: decide from thread-safe state, never wait on the main looper.
		 */
		boolean onStopSelf(String processName, String componentClass, int startId);

		/**
		 * In-loader backends (fake) report live instances so Service.stopSelf() routes to the
		 * authoritative record; real child-process backends never call these.
		 */
		void onInstanceHosted(String processName, String componentClass, Service instance);

		void onInstanceReleased(String processName, String componentClass, Service instance);
	}

	void setProcessDeathListener(ProcessDeathListener listener);

	void setChildEventListener(ChildEventListener listener);

	/** Runs child onCreate if needed, then onStartCommand(intent, flags, startId); reports its return. */
	void startService(String processName, String componentClass, Intent intent, int startId, Callback<Integer> callback);

	/**
	 * Flags-carrying form of {@link #startService(String, String, Intent, int, Callback)}
	 * (r3 delta): the controller computes the redelivery flags (START_FLAG_RETRY /
	 * START_FLAG_REDELIVERY) from its own R13 bookkeeping and passes them here, the
	 * way AOSP parcels them in ServiceStartArgs (ActiveServices.sendServiceArgsLocked
	 * 6696-6702). Added as a default so the frozen four-argument shape (and its
	 * lane-D implementors) is unchanged: a host without flags support delivers 0.
	 */
	default void startService(String processName, String componentClass, Intent intent, int flags, int startId, Callback<Integer> callback) {
		startService(processName, componentClass, intent, startId, callback);
	}

	/** Runs child onCreate if needed, then a fresh onBind(intent); reports the binder (may be null). */
	void bindService(String processName, String componentClass, Intent intent, Callback<IBinder> callback);

	/**
	 * Flags-carrying form of {@link #bindService(String, String, Intent, Callback)}
	 * (r4 delta): the caller's {@code Context.BIND_*} flags travel to the host so it
	 * can honour {@code BIND_AUTO_CREATE}. A host handed flags without
	 * {@code BIND_AUTO_CREATE} for a component it has no instance of must NOT create
	 * one; it reports no binder instead (AOSP {@code ActiveServices.bindServiceLocked}
	 * only calls {@code bringUpServiceLocked} under {@code BIND_AUTO_CREATE}, and
	 * {@code LoadedApk.ServiceDispatcher} never sees a connect for the suppressed
	 * creation). Added as a default so the frozen four-argument shape and its
	 * implementors are unchanged: a host without flags support keeps the old
	 * always-create behaviour.
	 */
	default void bindService(String processName, String componentClass, Intent intent, int flags, Callback<IBinder> callback) {
		bindService(processName, componentClass, intent, callback);
	}

	/** Runs child onRebind(intent) for an existing identity binding (same filterEquals identity). */
	void rebindService(String processName, String componentClass, Intent intent);

	/** Runs child onUnbind(intent); reports its return. */
	void unbindService(String processName, String componentClass, Intent intent, Callback<Boolean> callback);

	/** Runs child onDestroy and drops the instance; the child process may exit when empty. */
	void destroyService(String processName, String componentClass);

	/** True while the named process hosts at least one live (spawned, not killed/destroyed) instance. */
	boolean isProcessUp(String processName);
}
