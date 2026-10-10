/* SPDX-License-Identifier: GPL-3.0-only */
package android.atl;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Registry of same-process Android services, keyed by {@link ComponentName}.
 *
 * <p>One {@link ServiceRecord} per component holds the live {@link Service}
 * instance (null until {@code onCreate()} has run), the last start ID handed out
 * by {@code startService}, a {@code started} flag (a start request is still
 * outstanding, so the service outlives its bound clients) and the list of bound
 * clients in the order their {@code bindService} calls arrived. The result of the
 * single {@code onBind()} call per instance is cached on the record so a second
 * client never re-invokes {@code onBind()}.
 *
 * <p>Locking: {@link #lock} is only held for map and counter mutations. It is
 * never held while application code ({@code onCreate}/{@code onBind}/
 * {@code onServiceConnected}/{@code onUnbind}/{@code onDestroy}) is invoked, so a
 * re-entrant bind/unbind from inside a service callback cannot deadlock.
 */
public final class ATLServiceRegistry {

	/** One registered client of a bound service. */
	public static final class BoundClient {
		public final ServiceConnection connection;
		public final Intent intent;
		/** Set once onServiceConnected()/onNullBinding() was delivered. */
		public boolean delivered;

		BoundClient(ServiceConnection connection, Intent intent) {
			this.connection = connection;
			this.intent = intent;
		}
	}

	/** Per-component state. */
	public static final class ServiceRecord {
		public final ComponentName component;
		public final String className;
		/** Live instance; set before onCreate() runs, null until then. */
		public volatile Service instance;
		/** Last start ID delivered by startService; 0 before the first start. */
		public int lastStartId;
		/** Set once the record was detached by stopService/self-stop/last unbind. */
		public volatile boolean destroyed;
		/** True while a startService() request is outstanding. */
		public volatile boolean started;
		/** True once onBind() ran for the current instance. */
		public volatile boolean binderResolved;
		/** Cached onBind() result; null when the service returned a null binder. */
		public volatile IBinder binder;
		/** Bound clients, in the order their bindService() calls arrived. */
		public final ArrayList<BoundClient> bindings = new ArrayList<>();

		ServiceRecord(ComponentName component) {
			this.component = component;
			this.className = component.getClassName();
		}
	}

	/** Outcome of {@link #stop}. */
	public static final class StopResult {
		/** True when a record existed, i.e. stopService() must return true. */
		public final boolean running;
		/** True when the record was kept alive by remaining bound clients. */
		public final boolean keptBound;
		/** The detached record the caller must destroy; null when kept alive. */
		public final ServiceRecord record;

		StopResult(boolean running, boolean keptBound, ServiceRecord record) {
			this.running = running;
			this.keptBound = keptBound;
			this.record = record;
		}
	}

	/** Outcome of {@link #unbind}. */
	public static final class UnbindResult {
		/** The record to tear down (last client, not started); null when it stays. */
		public final ServiceRecord record;
		/** The intent of the unbound binding, used for onUnbind(). */
		public final Intent intent;

		UnbindResult(ServiceRecord record, Intent intent) {
			this.record = record;
			this.intent = intent;
		}
	}

	private final Object lock = new Object();
	private final HashMap<ComponentName, ServiceRecord> records = new HashMap<>();

	/**
	 * Registers a start request and allocates its start ID. The first start of a
	 * component is ID 1 and each following start increments it, matching
	 * AOSP's per-{@code ServiceRecord} monotonic counter.
	 */
	public ServiceRecord allocateStart(ComponentName component) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				record = new ServiceRecord(component);
				records.put(component, record);
			}
			record.lastStartId++;
			record.started = true;
			return record;
		}
	}

	/** Registers a bind request; does not allocate a start ID. */
	public ServiceRecord allocateBind(ComponentName component) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				record = new ServiceRecord(component);
				records.put(component, record);
			}
			return record;
		}
	}

	/** True while {@code record} is still the registered, not-yet-stopped state. */
	public boolean isLive(ServiceRecord record) {
		synchronized (lock) {
			return !record.destroyed && records.get(record.component) == record;
		}
	}

	/**
	 * Returns the registered record owning {@code instance}, or null when the
	 * instance is not registered (never brought up, or already stopped). Matches
	 * by object identity so two instances of the same component are never
	 * confused.
	 */
	public ServiceRecord findByInstance(Service instance) {
		synchronized (lock) {
			for (ServiceRecord record : records.values()) {
				if (record.instance == instance) {
					return record;
				}
			}
			return null;
		}
	}

	/** The last start ID allocated for {@code record}; 0 before the first start. */
	public int lastStartIdOf(ServiceRecord record) {
		synchronized (lock) {
			return record.lastStartId;
		}
	}

	/**
	 * Registers {@code connection} as a client of {@code record} unless the same
	 * connection is already registered, so a repeated bind never duplicates the
	 * delivery (Android keys bindings by connection, not by call site).
	 */
	public BoundClient registerBinding(ServiceRecord record, ServiceConnection connection, Intent intent) {
		synchronized (lock) {
			for (BoundClient client : record.bindings) {
				if (client.connection == connection) {
					return client;
				}
			}
			BoundClient client = new BoundClient(connection, intent);
			record.bindings.add(client);
			return client;
		}
	}

	/** Snapshot of {@code record}'s clients in registration order. */
	public ArrayList<BoundClient> bindingsOf(ServiceRecord record) {
		synchronized (lock) {
			return new ArrayList<>(record.bindings);
		}
	}

	/**
	 * stopService() bookkeeping. When bound clients remain the record is kept
	 * alive and only its started flag is cleared (Android keeps a bound service
	 * alive across a stopService until the last client unbinds); otherwise the
	 * record is detached and returned for the caller to destroy.
	 */
	public StopResult stop(ComponentName component) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				return new StopResult(false, false, null);
			}
			if (!record.bindings.isEmpty()) {
				record.started = false;
				return new StopResult(true, true, null);
			}
			records.remove(component);
			record.destroyed = true;
			return new StopResult(true, false, record);
		}
	}

	/**
	 * Removes {@code connection} from whichever record holds it. Returns the record
	 * to tear down when this was the last client of a not-started service (the
	 * caller then posts {@code onUnbind} + {@code onDestroy}); returns a null
	 * record when other clients remain or the service is kept alive by a
	 * startService() request. Throws when the connection was never registered.
	 */
	public UnbindResult unbind(ServiceConnection connection) {
		synchronized (lock) {
			ServiceRecord found = null;
			Intent intent = null;
			for (ServiceRecord record : records.values()) {
				for (Iterator<BoundClient> it = record.bindings.iterator(); it.hasNext();) {
					BoundClient client = it.next();
					if (client.connection == connection) {
						intent = client.intent;
						it.remove();
						found = record;
						break;
					}
				}
				if (found != null) {
					break;
				}
			}
			if (found == null) {
				throw new IllegalArgumentException("Service not connected: " + connection);
			}
			if (!found.bindings.isEmpty() || found.started) {
				return new UnbindResult(null, intent);
			}
			records.remove(found.component);
			found.destroyed = true;
			return new UnbindResult(found, intent);
		}
	}

	/**
	 * Removes {@code record} (the self-stop path) when it is still the registered
	 * record for its component. Returns false when it was already detached (e.g.
	 * a racing explicit stopService), so the caller does not destroy twice.
	 */
	public boolean detach(ServiceRecord record) {
		synchronized (lock) {
			if (records.get(record.component) != record) {
				return false;
			}
			records.remove(record.component);
			record.destroyed = true;
			return true;
		}
	}
}
