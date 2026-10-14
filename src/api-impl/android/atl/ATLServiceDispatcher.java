/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Extracted from AOSP frameworks-base android-17.0.0_r1 (94b4c163),
 * core/java/android/app/LoadedApk.java 2260-2517 (LoadedApk.ServiceDispatcher:
 * connected/death/doConnected/doDeath/doForget, RunConnection, DeathMonitor),
 * java-side client contract only. The IServiceConnection binder wrapper, the
 * Executor variant (ATL keeps ContextImpl's ExecutorConnection wrapper as the
 * client-visible connection) and the leak-location bookkeeping have no
 * counterpart here and are not carried over. Algorithms are extracted, not
 * imported wholesale.
 */
package android.atl;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;

/**
 * Client-side delivery state of one (Context, ServiceConnection) registration.
 *
 * <p>One dispatcher serves every component a connection is bound to and keeps,
 * per component, the binder it last reported. That per-component state is what
 * suppresses a duplicate {@code onServiceConnected()} for an unchanged binder,
 * turns a replacement binder into {@code onServiceDisconnected()} followed by a
 * fresh connect, reports a binder death as {@code onServiceDisconnected()} only
 * (never {@code onBindingDied()}, which is reserved for a binding torn down while
 * clients are still registered) and drops everything a forgotten registration
 * would still receive late.
 *
 * <p>{@link #doConnected}/{@link #doDeath} run the client callbacks on the
 * calling thread, outside this dispatcher's monitor, so a callback may re-enter
 * the dispatcher (bind or unbind from {@code onServiceConnected()}).
 * {@link #connected}/{@link #death} are the AOSP entry points for callers on
 * other threads (binder death notifications) and hop to the main looper first.
 * The {@link ServiceConnection} is whatever the registration stored: for the
 * executor variant of {@code bindService} that is ContextImpl's executor
 * wrapper, so executor dispatch happens inside the callback exactly as before.
 */
public final class ATLServiceDispatcher {
	/** LoadedApk.ServiceDispatcher.ConnectionInfo: what was last reported for one component. */
	private static final class ConnectionInfo {
		IBinder binder;
		IBinder.DeathRecipient deathMonitor;
	}

	private final Context mContext;
	private final ServiceConnection mConnection;
	private final Handler mMainHandler;
	private final ArrayMap<ComponentName, ConnectionInfo> mActiveConnections = new ArrayMap<>();
	private boolean mForgotten;

	public ATLServiceDispatcher(Context context, ServiceConnection conn, Handler mainHandler) {
		mContext = context;
		mConnection = conn;
		mMainHandler = mainHandler;
	}

	/** The context the registration was issued from (LoadedApk.ServiceDispatcher.mContext). */
	public Context getContext() {
		return mContext;
	}

	/** The connection the callbacks are delivered to. */
	public ServiceConnection getServiceConnection() {
		return mConnection;
	}

	/**
	 * Forgets every component's binder: no death notification can reach this
	 * registration any more and a connection that arrives late is ignored
	 * (AOSP {@code ServiceDispatcher.doForget}). Delivers no callback.
	 */
	public void doForget() {
		synchronized (this) {
			for (int i = 0; i < mActiveConnections.size(); i++) {
				ConnectionInfo ci = mActiveConnections.valueAt(i);
				ci.binder.unlinkToDeath(ci.deathMonitor, 0);
			}
			mActiveConnections.clear();
			mForgotten = true;
		}
	}

	/** AOSP {@code ServiceDispatcher.connected}: queues {@link #doConnected} on the main looper. */
	public void connected(final ComponentName name, final IBinder service, final boolean dead) {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				doConnected(name, service, dead);
			}
		});
	}

	/** AOSP {@code ServiceDispatcher.death}: queues {@link #doDeath} on the main looper. */
	public void death(final ComponentName name, final IBinder service) {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				doDeath(name, service);
			}
		});
	}

	/**
	 * Reports {@code service} (null: {@code onBind()} returned no binder) as the
	 * binder of {@code name}. {@code dead} marks a binding torn down while clients
	 * were registered: it reports {@code onBindingDied()} instead of a connect.
	 * Port of {@code ServiceDispatcher.doConnected} (LoadedApk.java 2402-2459).
	 */
	public void doConnected(ComponentName name, IBinder service, boolean dead) {
		ConnectionInfo old;
		ConnectionInfo info;

		synchronized (this) {
			if (mForgotten) {
				// We unbound before receiving the connection; ignore
				// any connection received.
				return;
			}
			old = mActiveConnections.get(name);
			if (old != null && old.binder == service) {
				// Huh, already have this one.  Oh well!
				return;
			}

			if (service != null) {
				// A new service is being connected... set it all up.
				info = new ConnectionInfo();
				info.binder = service;
				info.deathMonitor = new DeathMonitor(name, service);
				try {
					service.linkToDeath(info.deathMonitor, 0);
					mActiveConnections.put(name, info);
				} catch (RemoteException e) {
					// This service was dead before we got it...  just
					// don't do anything with it.
					mActiveConnections.remove(name);
					return;
				}

			} else {
				// The named service is being disconnected... clean up.
				mActiveConnections.remove(name);
			}

			if (old != null) {
				old.binder.unlinkToDeath(old.deathMonitor, 0);
			}
		}

		// If there was an old service, it is now disconnected.
		if (old != null) {
			mConnection.onServiceDisconnected(name);
		}
		if (dead) {
			mConnection.onBindingDied(name);
		} else {
			// If there is a new viable service, it is now connected.
			if (service != null) {
				mConnection.onServiceConnected(name, service);
			} else {
				// The binding machinery worked, but the remote returned null from onBind().
				mConnection.onNullBinding(name);
			}
		}
	}

	/**
	 * Reports that {@code service}, the binder last reported for {@code name}, died.
	 * A death for any other binder is ignored. Delivers {@code onServiceDisconnected()}
	 * only: the binding itself survives and is re-established by the next connect
	 * (port of {@code ServiceDispatcher.doDeath}, LoadedApk.java 2461-2474).
	 */
	public void doDeath(ComponentName name, IBinder service) {
		synchronized (this) {
			ConnectionInfo old = mActiveConnections.get(name);
			if (old == null || old.binder != service) {
				// Death for someone different than who we last
				// reported...  just ignore it.
				return;
			}
			mActiveConnections.remove(name);
			old.binder.unlinkToDeath(old.deathMonitor, 0);
		}

		mConnection.onServiceDisconnected(name);
	}

	/** LoadedApk.ServiceDispatcher.DeathMonitor: routes a binder death back through {@link #death}. */
	private final class DeathMonitor implements IBinder.DeathRecipient {
		final ComponentName mName;
		final IBinder mService;

		DeathMonitor(ComponentName name, IBinder service) {
			mName = name;
			mService = service;
		}

		@Override
		public void binderDied() {
			death(mName, mService);
		}
	}
}
