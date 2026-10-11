/* SPDX-License-Identifier: GPL-3.0-only */
package android.atl;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
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
		/**
		 * The context the bind was issued from (AOSP {@code getOuterContext()},
		 * which LoadedApk keys its dispatcher registry by). A Service instance
		 * that bound from its own context therefore owns this registration: when
		 * that instance is destroyed the registration is forgotten with it.
		 */
		public final Context context;
		/** Set once onServiceConnected()/onNullBinding() was delivered. */
		public boolean delivered;
		public IBinder deliveredBinder;
		/** Set once this bind request has been processed on the main looper. */
		public boolean requested;
		/** Set when onServiceConnected() must be followed by onRebind(). */
		public boolean rebind;

		BoundClient(ServiceConnection connection, Intent intent, Context context) {
			this.connection = connection;
			this.intent = intent;
			this.context = context;
		}
	}

	/** Per-component lifecycle state. */
	public static final class ServiceRecord {
		public final ComponentName component;
		public final String className;
		/** Live instance; set before onCreate() runs, null until then. */
		public volatile Service instance;
		/**
		 * The context the live instance was attached with. Bindings issued from
		 * it belong to that instance and are dropped when it is destroyed.
		 */
		public volatile Context instanceContext;
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
		// A service may return a different binder (including null) per Intent identity.
		public final ArrayList<Intent> binderIntents = new ArrayList<>();
		public final ArrayList<IBinder> identityBinders = new ArrayList<>();
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
		public final ArrayList<BoundClient> deliveredConnections = new ArrayList<>();

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
	public BoundClient registerBinding(ServiceRecord record, ServiceConnection connection, Intent intent,
	                                   Context context) {
		synchronized (lock) {
			for (BoundClient client : record.bindings) {
				if (client.context == context && client.connection == connection && sameIdentity(client.intent, intent)) {
					return client;
				}
			}
			BoundClient client = new BoundClient(connection, intent, context);
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

	public void cacheBinder(ServiceRecord record, Intent intent, IBinder binder) {
		synchronized (lock) {
			for (int i = 0; i < record.binderIntents.size(); i++) {
				if (sameIdentity(record.binderIntents.get(i), intent)) {
					record.identityBinders.set(i, binder);
					return;
				}
			}
			record.binderIntents.add(new Intent(intent));
			record.identityBinders.add(binder);
		}
	}

	public IBinder binderFor(ServiceRecord record, Intent intent) {
		synchronized (lock) {
			for (int i = 0; i < record.binderIntents.size(); i++)
				if (sameIdentity(record.binderIntents.get(i), intent))
					return record.identityBinders.get(i);
			return null;
		}
	}

	/** Last binder delivered to this context's dispatcher for this component. */
	public BoundClient deliveredConnection(ServiceRecord record, BoundClient client) {
		synchronized (lock) {
			for (BoundClient old : record.deliveredConnections)
				if (old.connection == client.connection && old.context == client.context) return old;
			return null;
		}
	}

	public void markConnectionDelivered(ServiceRecord record, BoundClient client, IBinder binder) {
		synchronized (lock) {
			BoundClient old = deliveredConnection(record, client);
			if (old != null) record.deliveredConnections.remove(old);
			client.deliveredBinder = binder;
			record.deliveredConnections.add(client);
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
	public ArrayList<UnbindResult> unbind(Context context, ServiceConnection connection) {
		ArrayList<UnbindResult> results = new ArrayList<>();
		synchronized (lock) {
			for (ServiceRecord record : new ArrayList<>(records.values())) {
				ArrayList<Intent> intents = new ArrayList<>();
				boolean removed = false;
				for (Iterator<BoundClient> it = record.bindings.iterator(); it.hasNext();) {
					BoundClient client = it.next();
					if (client.connection != connection || client.context != context) continue;
					it.remove();
					removed = true;
					if (!identityHasClientsLocked(record, client.intent)) {
						removeIdentityLocked(record, client.intent);
						addIdentityDistinct(intents, client.intent);
					}
				}
				if (!removed) continue;
				for (Iterator<BoundClient> it = record.deliveredConnections.iterator(); it.hasNext();) {
					BoundClient client = it.next();
					if (client.connection == connection && client.context == context) it.remove();
				}
				boolean destroy = !record.started && record.bindings.isEmpty();
				if (destroy) {
					records.remove(record.component);
					record.destroyed = true;
				}
				results.add(new UnbindResult(record, intents, destroy));
			}
			if (results.isEmpty())
				throw new IllegalArgumentException("Service not registered: " + connection);
		}
		return results;
	}

	/**
	 * LoadedApk.removeContextRegistrations() analogue for a destroyed Service's
	 * context: removes every client that bound from {@code context} from whichever
	 * record holds it, and returns one {@link UnbindResult} per affected record in
	 * the shape {@link #unbind} produces, so the caller posts the
	 * {@code onUnbind()}/{@code onDestroy()} work. A record left with no clients
	 * and no outstanding start request is detached and reported with
	 * {@code destroy} set, exactly as an explicit unbind of its last client would.
	 * Android does this when a destroyed Service's context is finalized: the
	 * leaked registration is unbound on the system side and the client-side
	 * dispatcher is forgotten, so the connection is gone from the client's point
	 * of view too.
	 */
	public ArrayList<UnbindResult> unbindContext(Context context) {
		ArrayList<UnbindResult> results = new ArrayList<>();
		if (context == null) {
			return results;
		}
		synchronized (lock) {
			for (ServiceRecord record : new ArrayList<>(records.values())) {
				ArrayList<Intent> intents = new ArrayList<>();
				boolean removed = false;
				for (Iterator<BoundClient> it = record.bindings.iterator(); it.hasNext();) {
					BoundClient client = it.next();
					if (client.context != context) {
						continue;
					}
					it.remove();
					removed = true;
					if (!identityHasClientsLocked(record, client.intent)) {
						removeIdentityLocked(record, client.intent);
						addIdentityDistinct(intents, client.intent);
					}
				}
				if (!removed) {
					continue;
				}
				for (Iterator<BoundClient> it = record.deliveredConnections.iterator(); it.hasNext();)
					if (it.next().context == context) it.remove();
				boolean destroy = !record.started && record.bindings.isEmpty();
				if (destroy) {
					records.remove(record.component);
					record.destroyed = true;
				}
				results.add(new UnbindResult(record, intents, destroy));
			}
		}
		return results;
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
	 * Clears the start request when the instance and start ID still match.
	 * Bound clients keep the service alive, just as with stopService().
	 */
	public StopResult stopSelf(ServiceRecord record, int startId) {
		synchronized (lock) {
			if (records.get(record.component) != record ||
			    (startId >= 0 && record.lastStartId != startId)) {
				return new StopResult(false, false, null);
			}
			return stop(record.component);
		}
	}
}
