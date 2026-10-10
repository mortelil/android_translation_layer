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
 * <p>One {@link ServiceRecord} per component lifecycle holds the live
 * {@link Service} instance (null until {@code onCreate()} has run), the last
 * start ID handed out by {@code startService}, a {@code started} flag (a start
 * request is still outstanding, so the service outlives its bound clients) and
 * the list of bound clients in the order their {@code bindService} calls
 * arrived. A client entry is identified by (connection, binding identity) where
 * the identity is the {@link Intent#filterEquals} relation of the bind intent:
 * Android keys bindings by {@code Intent.FilterComparison}, so extras do not
 * create a new binding identity while a different action does.
 *
 * <p>{@code onBind()} runs once per binding identity on the live instance (the
 * caller resolves identities through {@link #hasBindingIdentity} /
 * {@link #addBindingIdentity}), and the teardown delivers one {@code onUnbind()}
 * per registered identity, matching AOSP. The per-instance binder itself is
 * cached by the service in the probe, so repeated {@code onBind()} calls return
 * the same object.
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
		/** Set once this bind request has been processed on the main looper. */
		public boolean requested;
		/** Set when onServiceConnected() must be followed by onRebind(). */
		public boolean rebind;

		BoundClient(ServiceConnection connection, Intent intent) {
			this.connection = connection;
			this.intent = intent;
		}
	}

	/** Per-component lifecycle state. */
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
		/** True once onBind() resolved a binder for the current instance. */
		public volatile boolean binderResolved;
		/** Cached onBind() result; null when the service returned a null binder. */
		public volatile IBinder binder;
		/** Bound clients, in the order their bindService() calls arrived. */
		public final ArrayList<BoundClient> bindings = new ArrayList<>();
		/** Binding identities (filterEquals) onBind() was already run for. */
		public final ArrayList<Intent> boundIdentities = new ArrayList<>();
		/**
		 * Binding identities whose last client unbind ran onUnbind() returning
		 * true: the next bind of such an identity delivers onRebind() instead of
		 * onBind() while this instance survives.
		 */
		public final ArrayList<Intent> rebindPending = new ArrayList<>();
		/** Connections already notified for the current instance. */
		public final ArrayList<ServiceConnection> deliveredConnections = new ArrayList<>();

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
		/** The record the connection was unbound from (never null). */
		public final ServiceRecord record;
		/**
		 * The distinct binding identities whose last client was removed, in
		 * removal order, for onUnbind(); an identity that still has other clients
		 * is not included.
		 */
		public final ArrayList<Intent> intents;
		/**
		 * True when the instance must now be destroyed: it was not kept alive by a
		 * startService() request and no bound clients remain.
		 */
		public final boolean destroy;

		UnbindResult(ServiceRecord record, ArrayList<Intent> intents, boolean destroy) {
			this.record = record;
			this.intents = intents;
			this.destroy = destroy;
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
	 * Registers {@code connection} as a client of {@code record} under the binding
	 * identity of {@code intent}. A repeated bind of the same connection with an
	 * {@link Intent#filterEquals}-equal intent is deduplicated (Android keys
	 * bindings by the intent comparison, so extras do not matter); a different
	 * action is a new binding identity and returns a fresh entry. The identity is
	 * NOT recorded here: the caller records it through
	 * {@link #addBindingIdentity} once {@code onBind()} has actually run, so a bind
	 * never suppresses its own {@code onBind()}.
	 */
	public BoundClient registerBinding(ServiceRecord record, ServiceConnection connection, Intent intent) {
		synchronized (lock) {
			for (BoundClient client : record.bindings) {
				if (client.connection == connection && sameIdentity(client.intent, intent)) {
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

	/** True when onBind() already ran for {@code intent}'s identity. */
	public boolean hasBindingIdentity(ServiceRecord record, Intent intent) {
		synchronized (lock) {
			for (Intent bound : record.boundIdentities) {
				if (sameIdentity(bound, intent)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Records that onBind() was run for {@code intent}'s binding identity. */
	public void addBindingIdentity(ServiceRecord record, Intent intent) {
		synchronized (lock) {
			addIdentityLocked(record, intent);
		}
	}

	/** True when {@code connection} was already delivered a binder for this instance. */
	public boolean connectionDelivered(ServiceRecord record, ServiceConnection connection) {
		synchronized (lock) {
			return record.deliveredConnections.contains(connection);
		}
	}

	/** Marks {@code connection} as having been delivered a binder for this instance. */
	public void markConnectionDelivered(ServiceRecord record, ServiceConnection connection) {
		synchronized (lock) {
			if (!record.deliveredConnections.contains(connection)) {
				record.deliveredConnections.add(connection);
			}
		}
	}

	/** True when {@code intent}'s identity is pending an onRebind() delivery. */
	public boolean consumeRebindPending(ServiceRecord record, Intent intent) {
		synchronized (lock) {
			for (Iterator<Intent> it = record.rebindPending.iterator(); it.hasNext();) {
				if (sameIdentity(it.next(), intent)) {
					it.remove();
					return true;
				}
			}
			return false;
		}
	}

	/** Records that onUnbind() ran for {@code intent}'s identity and returned true. */
	public void markRebindPending(ServiceRecord record, Intent intent) {
		synchronized (lock) {
			if (intent == null || record.destroyed) {
				return;
			}
			for (Intent pending : record.rebindPending) {
				if (sameIdentity(pending, intent)) {
					return;
				}
			}
			record.rebindPending.add(intent);
		}
	}

	private static boolean identityHasClientsLocked(ServiceRecord record, Intent intent) {
		for (BoundClient client : record.bindings) {
			if (sameIdentity(client.intent, intent)) {
				return true;
			}
		}
		return false;
	}

	/** Drops every record of {@code intent}'s identity: it is no longer onBind()-bound. */
	private static void removeIdentityLocked(ServiceRecord record, Intent intent) {
		for (Iterator<Intent> it = record.boundIdentities.iterator(); it.hasNext();) {
			if (sameIdentity(it.next(), intent)) {
				it.remove();
			}
		}
		for (Iterator<Intent> it = record.rebindPending.iterator(); it.hasNext();) {
			if (sameIdentity(it.next(), intent)) {
				it.remove();
			}
		}
	}

	private void addIdentityLocked(ServiceRecord record, Intent intent) {
		if (intent == null) {
			return;
		}
		for (Intent bound : record.boundIdentities) {
			if (sameIdentity(bound, intent)) {
				return;
			}
		}
		record.boundIdentities.add(intent);
	}

	private static boolean sameIdentity(Intent a, Intent b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		return a.filterEquals(b);
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
	 * Removes every binding entry of {@code connection} (Android unbinds the whole
	 * connection, however many distinct identities it registered). The removed
	 * identities whose last client this was are returned for {@code onUnbind()}
	 * even when the instance survives for other clients or a startService()
	 * request; the {@code destroy} flag is set only when it was the last client of
	 * a service that is not kept alive by a startService() request, in which case
	 * the record is detached here and the caller then posts {@code onDestroy()}.
	 * Throws when the connection was never registered.
	 */
	public UnbindResult unbind(ServiceConnection connection) {
		synchronized (lock) {
			ServiceRecord found = null;
			ArrayList<Intent> intents = new ArrayList<>();
			for (ServiceRecord record : records.values()) {
				for (Iterator<BoundClient> it = record.bindings.iterator(); it.hasNext();) {
					BoundClient client = it.next();
					if (client.connection == connection) {
						it.remove();
						found = record;
						// Android runs one onUnbind() per binding identity once
						// that identity's connections are all gone, independently
						// of other identities or a leftover start request.
						if (!identityHasClientsLocked(record, client.intent)) {
							removeIdentityLocked(record, client.intent);
							addIdentityDistinct(intents, client.intent);
						}
					}
				}
				if (found != null) {
					break;
				}
			}
			if (found == null) {
				// AOSP LoadedApk.forgetServiceDispatcher: the client removes its
				// dispatcher first and throws this exact message for an unknown
				// connection (ContextImpl.unbindService).
				throw new IllegalArgumentException("Service not registered: " + connection);
			}
			boolean destroy = !found.started && found.bindings.isEmpty();
			if (destroy) {
				records.remove(found.component);
				found.destroyed = true;
			}
			return new UnbindResult(found, intents, destroy);
		}
	}

	private static void addIdentityDistinct(ArrayList<Intent> intents, Intent intent) {
		if (intent == null) {
			intents.add(null);
			return;
		}
		for (Intent existing : intents) {
			if (sameIdentity(existing, intent)) {
				return;
			}
		}
		intents.add(intent);
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
