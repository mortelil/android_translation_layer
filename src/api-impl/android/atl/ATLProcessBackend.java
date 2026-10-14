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
 * {@link Callback#onHostUnavailable()} means the hosting process could not be
 * spawned.
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
	}

	/**
	 * Reports that a hosting process died while it hosted at least one
	 * instance. {@code generation} is the backend's per-process generation
	 * after this death (monotonic from 1); callbacks belonging to an earlier
	 * generation are stale and must be ignored by the controller.
	 */
	interface ProcessDeathListener {
		void onProcessDied(String processName, int generation);
	}

	/** Reports events that originate inside the hosting side rather than from a controller call. */
	interface ChildEventListener {
		/** Child-side stopSelf()/stopSelfResult() report (S09); controller decides authoritatively. */
		void onStopSelf(String processName, String componentClass, int startId);

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

	/** Runs child onCreate if needed, then a fresh onBind(intent); reports the binder (may be null). */
	void bindService(String processName, String componentClass, Intent intent, Callback<IBinder> callback);

	/** Runs child onRebind(intent) for an existing identity binding (same filterEquals identity). */
	void rebindService(String processName, String componentClass, Intent intent);

	/** Runs child onUnbind(intent); reports its return. */
	void unbindService(String processName, String componentClass, Intent intent, Callback<Boolean> callback);

	/** Runs child onDestroy and drops the instance; the child process may exit when empty. */
	void destroyService(String processName, String componentClass);

	/** True while the named process hosts at least one live (spawned, not killed/destroyed) instance. */
	boolean isProcessUp(String processName);
}
