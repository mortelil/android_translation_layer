/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Remote-placement fields and methods (remoteProcess/remoteUp, failRemote,
 * recordsOfProcess, forgetRemoteProcessState, hasCachedBinder, the remoteProcess
 * parameter of allocateStart/allocateBind) are adapted from the Firefox lane's
 * ATLServiceRegistry (references/firefox @5418fb3; originally parallel-apps
 * firefox lane a9ebfe2, src/api-impl/android/atl/ATLServiceRegistry.java).
 *
 * The start bookkeeping and restart ladder (StartItem, RestartConstants,
 * takePendingStarts ... planRestart) are algorithms extracted from AOSP
 * frameworks-base android-17.0.0_r1 (94b4c163):
 * services/core/java/com/android/server/am/ActiveServices.java
 * (scheduleServiceRestartLocked, performServiceRestartLocked,
 * sendServiceArgsLocked, serviceDoneExecutingLocked),
 * ServiceRecord.java (StartItem, hasAutoCreateConnections, canStopIfKilled),
 * ProcessServiceRecord.java (incServiceCrashCountLocked) and
 * ActivityManagerConstants.java (restart constants). Not imported wholesale.
 */
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
 * <p>A record whose component the manifest places in a process of its own carries
 * that process as {@link ServiceRecord#remoteProcess}; the registry then holds
 * only the caller-facing state (start IDs, clients, binders) while the hosting
 * process (an {@link ATLProcessBackend}) holds the instance. Start requests are
 * queued as {@link StartItem}s and the restart ladder ({@link #planRestart}) is
 * the data side of AOSP's {@code ActiveServices} restart policy: the registry
 * only computes state and decisions, the controller performs the callbacks and
 * arms the timers.
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

		/**
		 * True when the bind asked for {@code BIND_AUTO_CREATE}: the client then keeps
		 * the service alive and a killed service is restarted for it (AOSP
		 * {@code ConnectionRecord.hasFlag(Context.BIND_AUTO_CREATE)}).
		 */
		public final boolean autoCreate;

		BoundClient(ServiceConnection connection, Intent intent, Context context, boolean autoCreate) {
			this.connection = connection;
			this.intent = intent;
			this.context = context;
			this.autoCreate = autoCreate;
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

		/**
		 * The manifest-declared process this component is placed in when it is
		 * not the loader's own process, or null while it runs in the loader
		 * process; set when the record is created. A remote record has no
		 * {@link #instance} here: its lifecycle runs in that process and its
		 * binder arrives as a proxy (Firefox registry, references/firefox @5418fb3).
		 */
		public final String remoteProcess;
		/**
		 * True once a remote record has been asked to come up - a start
		 * request, or a bind that auto-creates. This is the remote analogue of
		 * {@code instance != null}: a bind without BIND_AUTO_CREATE stays
		 * pending while it is false and is connected once it becomes true.
		 */
		public volatile boolean remoteUp;
		/**
		 * Bumped by {@link #forgetRemoteProcessState} every time the hosting process
		 * dies; a backend callback that captured an older value is stale.
		 */
		public volatile int generation;
		// Start bookkeeping (AOSP ServiceRecord, ServiceRecord.java 521-535 and the
		// pendingStarts/deliveredStarts/callStart/stopIfKilled fields).
		/** Start requests not yet handed to onStartCommand(). */
		public final ArrayList<StartItem> pendingStarts = new ArrayList<>();
		/** Start requests handed to onStartCommand() and not yet finished. */
		public final ArrayList<StartItem> deliveredStarts = new ArrayList<>();
		/** True once onStartCommand() has been called and its result recorded. */
		public boolean callStart;
		/** The last value onStartCommand() returned. */
		public int startCommandResult;
		/** True when a kill must not restart the service for its start requests. */
		public boolean stopIfKilled;
		/** True when the last restart plan dropped a start item for too many deliveries. */
		public boolean startCanceled;
		// Restart state (AOSP ServiceRecord restartCount/totalRestartCount/crashCount/
		// restartDelay/restartTime/nextRestartTime).
		public int restartCount;
		public int totalRestartCount;
		public int crashCount;
		public long restartDelay;
		/** Uptime of the last scheduled restart; 0 before the first. */
		public long restartTime;
		public long nextRestartTime;

		ServiceRecord(ComponentName component, String remoteProcess) {
			this.component = component;
			this.className = component.getClassName();
			this.remoteProcess = remoteProcess;
		}

		/** True when this record's component is hosted in a process of its own. */
		public boolean isRemote() {
			return remoteProcess != null;
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

	/**
	 * One queued or delivered {@code onStartCommand()} request. Extraction of AOSP
	 * {@code ServiceRecord.StartItem} (ServiceRecord.java 521-535) without the URI
	 * grant, caller and task-removed bookkeeping ATL has no counterpart for.
	 */
	public static final class StartItem {
		public final int id;
		/** Copy of the start intent; null for a fabricated sticky-restart item. */
		public final Intent intent;
		public int deliveryCount;
		public int doneExecutingCount;
		public long deliveredTime;

		StartItem(int id, Intent intent) {
			this.id = id;
			this.intent = intent;
		}
	}

	/**
	 * Restart tuning, defaults from AOSP {@code ActivityManagerConstants}
	 * (ActivityManagerConstants.java 222-230): SERVICE_RESTART_DURATION,
	 * SERVICE_RESTART_DURATION_FACTOR, SERVICE_RESET_RUN_DURATION,
	 * SERVICE_MIN_RESTART_TIME_BETWEEN, BOUND_SERVICE_CRASH_RESTART_DURATION,
	 * BOUND_SERVICE_MAX_CRASH_RETRY and MIN_CRASH_INTERVAL.
	 */
	public static final class RestartConstants {
		public long restartDurationMs = 1000;
		public long restartDurationFactor = 4;
		public long resetRunDurationMs = 60000;
		public long minRestartTimeBetweenMs = 10000;
		public long boundCrashRestartDurationMs = 1800000;
		/** Crash count at which the caller stops restarting and tears the service down. */
		public int boundCrashMaxRetry = 16;
		public long minCrashIntervalMs = 120000;
	}

	/** Outcome of {@link #planRestart}. */
	public static final class RestartPlan {
		/** True when a restart was scheduled; false means nothing needs the record. */
		public boolean scheduled;
		/** The uptime at which the restart is due (AOSP {@code nextRestartTime}). */
		public long nextRestartTime;
		/**
		 * True when the restart is only for auto-create connections: the explicit start
		 * is gone (stop-if-killed, nothing pending), so the caller clears {@code started}.
		 */
		public boolean droppedStart;
		/** True when a delivered start was dropped for too many deliveries. */
		public boolean canceledAnyStart;
	}

	/** ServiceRecord.MAX_DELIVERY_COUNT: deliveries of one start item before it is canceled. */
	public static final int MAX_DELIVERY_COUNT = 3;
	/** ServiceRecord.MAX_DONE_EXECUTING_COUNT: acknowledged redeliveries before the item is canceled. */
	public static final int MAX_DONE_EXECUTING_COUNT = 6;

	private final Object lock = new Object();
	private final HashMap<ComponentName, ServiceRecord> records = new HashMap<>();

	/**
	 * Records scheduled for restart, sorted by {@code nextRestartTime} (AOSP
	 * {@code ActiveServices.mRestartingServices}); inserted in order by {@link #planRestart},
	 * removed by {@link #clearRestarting} or when the record is detached.
	 */
	private final ArrayList<ServiceRecord> restarting = new ArrayList<>();

	/**
	 * Registers a start request and allocates its start ID. The first start of a
	 * component is ID 1 and each following start increments it, matching
	 * AOSP's per-{@code ServiceRecord} monotonic counter. The start is queued as a
	 * {@link StartItem} (carrying a copy of {@code intent}) on the record's
	 * {@code pendingStarts}, like AOSP {@code startServiceLocked} queueing a
	 * {@code ServiceRecord.StartItem}. {@code remoteProcess} is the
	 * manifest-declared process the component is placed in, or null for the
	 * loader's own process (set when the record is created); a remote record is
	 * marked up, because the start request is what brings its process' copy of the
	 * component up (Firefox registry {@code allocateStart}, references/firefox
	 * @5418fb3).
	 */
	public ServiceRecord allocateStart(ComponentName component, Intent intent, String remoteProcess) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				record = new ServiceRecord(component, remoteProcess);
				records.put(component, record);
			}
			record.lastStartId++;
			record.started = true;
			record.pendingStarts.add(new StartItem(record.lastStartId, intent == null ? null : new Intent(intent)));
			if (record.isRemote()) {
				record.remoteUp = true;
			}
			return record;
		}
	}

	/**
	 * Registers a bind request; does not allocate a start ID. {@code remoteProcess}
	 * is as for {@link #allocateStart}; a bind does not mark a remote record up
	 * (Firefox registry {@code allocateBind}, references/firefox @5418fb3).
	 */
	public ServiceRecord allocateBind(ComponentName component, String remoteProcess) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				record = new ServiceRecord(component, remoteProcess);
				records.put(component, record);
			}
			return record;
		}
	}

	/**
	 * Marks a record whose hosting process could not be brought up: the component
	 * never ran, so nothing may keep a record - or a client - waiting on a process
	 * that will not answer. The record is no longer up or started, its start items
	 * are void, and it is dropped once no client holds it (a client that still holds
	 * it keeps its later unbindService() answerable); a later bind resolves a fresh
	 * record and tries again. Port of the Firefox registry's {@code failRemote}
	 * (references/firefox @5418fb3); voiding the start items is the delta for
	 * the R13 bookkeeping, which Firefox did not have.
	 */
	public void failRemote(ServiceRecord record) {
		synchronized (lock) {
			record.remoteUp = false;
			record.started = false;
			record.pendingStarts.clear();
			record.deliveredStarts.clear();
			record.callStart = false;
			if (record.bindings.isEmpty() && records.get(record.component) == record) {
				records.remove(record.component);
				record.destroyed = true;
				restarting.remove(record);
			}
		}
	}

	/**
	 * Every live record the manifest places in {@code processName}. Port of the
	 * Firefox registry's {@code recordsOfProcess} (references/firefox @5418fb3).
	 */
	public ArrayList<ServiceRecord> recordsOfProcess(String processName) {
		ArrayList<ServiceRecord> placed = new ArrayList<>();
		if (processName == null) {
			return placed;
		}
		synchronized (lock) {
			for (ServiceRecord record : records.values()) {
				if (processName.equals(record.remoteProcess)) {
					placed.add(record);
				}
			}
		}
		return placed;
	}

	/**
	 * Forgets everything {@code record} holds about the hosting process that
	 * just died: every binder it ever resolved is a proxy into a dead process,
	 * so its identity answers, the connections already notified, and the armed
	 * rebinds are all void. The record itself stays - it is still the
	 * registered state - with {@code remoteUp} cleared, so the next bind that
	 * auto-creates connects to a fresh process. {@code generation} is bumped so a
	 * backend callback requested before the death can be recognised as stale.
	 * Port of the Firefox registry's {@code forgetRemoteProcessState}
	 * (references/firefox @5418fb3) plus the generation bump. Start items are
	 * deliberately kept: {@link #planRestart} requeues them.
	 */
	public void forgetRemoteProcessState(ServiceRecord record) {
		synchronized (lock) {
			record.generation++;
			record.remoteUp = false;
			record.binderResolved = false;
			record.binder = null;
			record.binderIntents.clear();
			record.identityBinders.clear();
			record.boundIdentities.clear();
			record.rebindPending.clear();
			record.deliveredConnections.clear();
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
	 * never suppresses its own {@code onBind()}. {@code autoCreate} records whether
	 * the bind carried {@code BIND_AUTO_CREATE} (see {@link #hasAutoCreateConnections});
	 * a deduplicated bind keeps the flag of the first registration.
	 */
	public BoundClient registerBinding(ServiceRecord record, ServiceConnection connection, Intent intent,
	                                   Context context, boolean autoCreate) {
		synchronized (lock) {
			for (BoundClient client : record.bindings) {
				if (client.context == context && client.connection == connection && sameIdentity(client.intent, intent)) {
					return client;
				}
			}
			BoundClient client = new BoundClient(connection, intent, context, autoCreate);
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

	/**
	 * True when {@code intent}'s identity has already been answered - its
	 * {@code onBind()} ran, here or in the hosting process, even if it returned
	 * no binder. {@link #binderFor} returning null cannot tell those apart, and
	 * a remote delivery must not report a null binding for an identity the
	 * hosting process has not answered yet. Port of the Firefox registry's
	 * {@code hasCachedBinder} (references/firefox @5418fb3).
	 */
	public boolean hasCachedBinder(ServiceRecord record, Intent intent) {
		synchronized (lock) {
			for (Intent cached : record.binderIntents) {
				if (sameIdentity(cached, intent)) {
					return true;
				}
			}
			return false;
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
			restarting.remove(record);
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
					restarting.remove(record);
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
					restarting.remove(record);
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

	// ---------------------------------------------------------------------
	// R13: start bookkeeping (AOSP ServiceRecord / ActiveServices)
	// ---------------------------------------------------------------------

	/**
	 * Allocates the next start ID without queueing a start request. AOSP
	 * {@code ServiceRecord.makeNextStartId()} (ServiceRecord.java 1592-1598); used
	 * for the null-intent item fabricated on a sticky restart.
	 */
	public int nextStartId(ServiceRecord record) {
		synchronized (lock) {
			return nextStartIdLocked(record);
		}
	}

	private static int nextStartIdLocked(ServiceRecord record) {
		record.lastStartId++;
		if (record.lastStartId < 1) {
			record.lastStartId = 1;
		}
		return record.lastStartId;
	}

	/** Sets or clears the started flag (AOSP {@code ServiceRecord.startRequested}). */
	public void setStarted(ServiceRecord record, boolean started) {
		synchronized (lock) {
			record.started = started;
		}
	}

	/**
	 * Snapshot and clear of {@code record}'s pending start items, in arrival
	 * order; the caller delivers them (AOSP
	 * {@code ActiveServices.sendServiceArgsLocked}, ActiveServices.java 6632-6703,
	 * which drains {@code r.pendingStarts}). Pass the returned list's original
	 * size to {@link #skipNullIntent} for each item and call
	 * {@link #markStartDelivered} for each item actually delivered.
	 */
	public ArrayList<StartItem> takePendingStarts(ServiceRecord record) {
		synchronized (lock) {
			ArrayList<StartItem> taken = new ArrayList<>(record.pendingStarts);
			record.pendingStarts.clear();
			return taken;
		}
	}

	/**
	 * Records that {@code item} is being delivered to onStartCommand(): stamps
	 * its delivery time, moves it onto {@code deliveredStarts} and counts the
	 * delivery (AOSP sendServiceArgsLocked 6672-6675).
	 */
	public void markStartDelivered(ServiceRecord record, StartItem item, long now) {
		synchronized (lock) {
			item.deliveredTime = now;
			record.deliveredStarts.add(item);
			item.deliveryCount++;
		}
	}

	/**
	 * The onStartCommand() {@code flags} for {@code item}: {@code START_FLAG_RETRY}
	 * when it was delivered before without being acknowledged, and
	 * {@code START_FLAG_REDELIVERY} when an earlier delivery was acknowledged
	 * with START_REDELIVER_INTENT (AOSP sendServiceArgsLocked 6696-6702). Must be
	 * computed after {@link #markStartDelivered} counted this delivery.
	 */
	public int startFlagsFor(StartItem item) {
		synchronized (lock) {
			int flags = 0;
			if (item.deliveryCount > 1) {
				flags |= Service.START_FLAG_RETRY;
			}
			if (item.doneExecutingCount > 0) {
				flags |= Service.START_FLAG_REDELIVERY;
			}
			return flags;
		}
	}

	/**
	 * True when {@code item} must not be delivered: a null-intent item in a batch
	 * that originally held more than one item is skipped, while a lone null
	 * intent is delivered to support {@code onStartCommand(null)} (AOSP
	 * sendServiceArgsLocked 6665-6671; {@code batchOriginalSize} is the size of
	 * the list {@link #takePendingStarts} returned).
	 */
	public boolean skipNullIntent(StartItem item, int batchOriginalSize) {
		return item.intent == null && batchOriginalSize > 1;
	}

	/**
	 * Fabricates the null-intent start item that makes a restarted sticky
	 * service's onStartCommand(null) run: only when the record is started,
	 * onStartCommand() was already called for it ({@code callStart}) and no
	 * start item is pending (AOSP performServiceRestartLocked 6023-6028; sticky
	 * null item, ActiveServices 6600-6602). The item is allocated its start ID
	 * here ({@code StartItem.id} is final, AOSP builds it with
	 * {@code makeNextStartId()}) and is queued on {@code pendingStarts}: deliver
	 * it through {@link #takePendingStarts}, not directly. Returns null when no
	 * item is needed.
	 */
	public StartItem stickyRestartItem(ServiceRecord record) {
		synchronized (lock) {
			if (!record.started || !record.callStart || !record.pendingStarts.isEmpty()) {
				return null;
			}
			StartItem item = new StartItem(nextStartIdLocked(record), null);
			record.pendingStarts.add(item);
			return item;
		}
	}

	/**
	 * Applies the value onStartCommand() returned for start {@code startId}
	 * (AOSP {@code ActiveServices.serviceDoneExecutingLocked}, SERVICE_DONE_EXECUTING_START,
	 * ActiveServices.java 7224-7280):
	 * <ul>
	 * <li>START_STICKY / START_STICKY_COMPATIBILITY: the delivered item is done
	 * (removed) and the record is not stop-if-killed;
	 * <li>START_NOT_STICKY: the delivered item is removed; the record is
	 * stop-if-killed when no later start was issued ({@code lastStartId == startId});
	 * <li>START_REDELIVER_INTENT: the item STAYS on {@code deliveredStarts} with its
	 * delivery count reset and {@code doneExecutingCount} incremented, and the
	 * record is stop-if-killed;
	 * <li>START_TASK_REMOVED_COMPLETE: no state change (ATL has no task-removed
	 * start items);
	 * <li>anything else: {@link IllegalArgumentException}, as in AOSP.
	 * </ul>
	 * {@code callStart} becomes true, except for START_STICKY_COMPATIBILITY, and
	 * {@code startCommandResult} records {@code res} (not for START_TASK_REMOVED_COMPLETE).
	 */
	public void onStartCommandResult(ServiceRecord record, int startId, int res) {
		synchronized (lock) {
			record.callStart = true;
			if (res != Service.START_TASK_REMOVED_COMPLETE) {
				record.startCommandResult = res;
			}
			switch (res) {
			case Service.START_STICKY_COMPATIBILITY:
			case Service.START_STICKY:
				findDeliveredStartLocked(record, startId, true);
				record.stopIfKilled = false;
				break;
			case Service.START_NOT_STICKY:
				findDeliveredStartLocked(record, startId, true);
				if (record.lastStartId == startId) {
					record.stopIfKilled = true;
				}
				break;
			case Service.START_REDELIVER_INTENT: {
				StartItem item = findDeliveredStartLocked(record, startId, false);
				if (item != null) {
					item.deliveryCount = 0;
					item.doneExecutingCount++;
					record.stopIfKilled = true;
				}
				break;
			}
			case Service.START_TASK_REMOVED_COMPLETE:
				break;
			default:
				throw new IllegalArgumentException("Unknown service start result: " + res);
			}
			if (res == Service.START_STICKY_COMPATIBILITY) {
				record.callStart = false;
			}
		}
	}

	/** AOSP {@code ServiceRecord.findDeliveredStart} (ServiceRecord.java 1575-1586), without task-removed items. */
	private static StartItem findDeliveredStartLocked(ServiceRecord record, int id, boolean remove) {
		for (int i = 0; i < record.deliveredStarts.size(); i++) {
			StartItem item = record.deliveredStarts.get(i);
			if (item.id == id) {
				if (remove) {
					record.deliveredStarts.remove(i);
				}
				return item;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------
	// R13: restart ladder (pure state math; the caller owns timers/callbacks)
	// ---------------------------------------------------------------------

	/**
	 * True if auto-create bindings keep {@code record} alive: some client that
	 * is still registered was bound with {@code BIND_AUTO_CREATE} (AOSP
	 * {@code ServiceRecord.hasAutoCreateConnections}, ServiceRecord.java 1540-1553,
	 * which scans the record's live {@code connections}).
	 */
	public boolean hasAutoCreateConnections(ServiceRecord record) {
		synchronized (lock) {
			return hasAutoCreateConnectionsLocked(record);
		}
	}

	private static boolean hasAutoCreateConnectionsLocked(ServiceRecord record) {
		for (BoundClient client : record.bindings) {
			if (client.autoCreate) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True if a killed record has any reason to run again: a start request or an
	 * auto-create client (AOSP performServiceRestartLocked 6003-6028, which
	 * refuses to restart a service nothing needs).
	 */
	public boolean restartNeeded(ServiceRecord record) {
		synchronized (lock) {
			return record.started || hasAutoCreateConnectionsLocked(record);
		}
	}

	/**
	 * Counts a crash of the process hosting {@code record}: a crash more than
	 * {@code constants.minCrashIntervalMs} after the record's last restart
	 * restarts the count at 1, a nearer one increments it (AOSP
	 * {@code ProcessServiceRecord.incServiceCrashCountLocked}, ProcessServiceRecord.java
	 * 236-260, for the record's {@code restartTime}).
	 */
	public void incServiceCrashCount(ServiceRecord record, long now, RestartConstants constants) {
		synchronized (lock) {
			if (now > record.restartTime + constants.minCrashIntervalMs) {
				record.crashCount = 1;
			} else {
				record.crashCount++;
			}
		}
	}

	/**
	 * Plans the restart of a killed record: AOSP
	 * {@code ActiveServices.scheduleServiceRestartLocked} (ActiveServices.java
	 * 5654-5823) with the restart backoff enabled, a non-persistent app and no extra
	 * memory-pressure delay (getExtraRestartTimeInBetweenLocked 5995-6001 is 0).
	 * Pure state math under the lock: the caller arms the timer at
	 * {@link RestartPlan#nextRestartTime}, tears the record down when nothing is
	 * scheduled, and clears the started flag when
	 * {@link RestartPlan#droppedStart} is set.
	 * <ol>
	 * <li>Delivered-but-unacknowledged starts go back to the front of
	 * {@code pendingStarts}, in order (5668-5686). A null-intent item is dropped
	 * silently (it is fabricated again if needed); with {@code allowCancel} an item
	 * that reached {@link #MAX_DELIVERY_COUNT} deliveries or
	 * {@link #MAX_DONE_EXECUTING_COUNT} acknowledgements is dropped as canceled.
	 * {@code minDuration} starts at {@code restartDurationMs} and is raised to
	 * twice the time each kept item has been running; the reset-run window is
	 * raised likewise (5662-5663, 5679-5683).
	 * <li>With {@code allowCancel}, a record that is stop-if-killed (or whose start
	 * was canceled) with nothing pending and no auto-create client is not
	 * restarted (5688-5695); a record that is neither started nor held by
	 * auto-create clients is not restarted either (the guard of
	 * performServiceRestartLocked 6010-6017, applied here so it never enters the
	 * restarting list).
	 * <li>Ladder (5697-5738): first restart takes {@code minDuration};
	 * {@code crashCount > 1} takes {@code boundCrashRestartDurationMs * (crashCount - 1)};
	 * a restart more than the reset-run window after {@code restartTime} starts over
	 * at {@code minDuration}; otherwise the delay is multiplied by
	 * {@code restartDurationFactor} and floored at {@code minDuration}.
	 * <li>Spacing (5740-5775): the record is moved to the end of the
	 * {@code nextRestartTime}-sorted restarting list and its slot is pushed past any
	 * other restarting record within {@code minRestartTimeBetweenMs}.
	 * </ol>
	 * The record's {@code restartTime} is set to {@code now}: AOSP stamps it at
	 * the service's next real start (ActiveServices.java 6497), which the
	 * registry cannot observe; the schedule time is the nearest equivalent.
	 * {@code remoteUp} is cleared because the hosting instance is gone.
	 */
	public RestartPlan planRestart(ServiceRecord record, boolean allowCancel, long now,
	                               RestartConstants constants) {
		RestartPlan plan = new RestartPlan();
		synchronized (lock) {
			long minDuration = constants.restartDurationMs;
			long resetTime = constants.resetRunDurationMs;
			boolean canceled = false;

			ArrayList<StartItem> requeue = new ArrayList<>();
			for (StartItem item : record.deliveredStarts) {
				if (item.intent == null) {
					continue;
				}
				if (!allowCancel || (item.deliveryCount < MAX_DELIVERY_COUNT
				                     && item.doneExecutingCount < MAX_DONE_EXECUTING_COUNT)) {
					requeue.add(item);
					long dur = (now - item.deliveredTime) * 2;
					if (minDuration < dur) minDuration = dur;
					if (resetTime < dur) resetTime = dur;
				} else {
					canceled = true;
				}
			}
			record.deliveredStarts.clear();
			record.pendingStarts.addAll(0, requeue);
			record.startCanceled = canceled;
			plan.canceledAnyStart = canceled;

			boolean autoCreate = hasAutoCreateConnectionsLocked(record);
			if (!record.started && !autoCreate) {
				return plan;
			}
			if (allowCancel && !autoCreate && canStopIfKilledLocked(record, canceled)) {
				return plan;
			}

			record.totalRestartCount++;
			if (record.restartDelay == 0) {
				record.restartCount++;
				record.restartDelay = minDuration;
			} else if (record.crashCount > 1) {
				record.restartDelay = constants.boundCrashRestartDurationMs * (record.crashCount - 1);
			} else if (now > record.restartTime + resetTime) {
				record.restartCount = 1;
				record.restartDelay = minDuration;
			} else {
				record.restartDelay *= constants.restartDurationFactor;
				if (record.restartDelay < minDuration) {
					record.restartDelay = minDuration;
				}
			}

			record.nextRestartTime = now + record.restartDelay;
			restarting.remove(record);
			if (!restarting.isEmpty()) {
				// Do not restart a bunch of services at the same time: the list is
				// sorted by nextRestartTime, so scan from the latest slot down.
				final long between = constants.minRestartTimeBetweenMs;
				boolean repeat;
				do {
					repeat = false;
					final long next = record.nextRestartTime;
					for (int i = restarting.size() - 1; i >= 0; i--) {
						final long other = restarting.get(i).nextRestartTime;
						if (next >= other - between && next < other + between) {
							record.nextRestartTime = other + between;
							record.restartDelay = record.nextRestartTime - now;
							repeat = true;
							break;
						} else if (next >= other + between) {
							break;
						}
					}
				} while (repeat);
			}
			int at = restarting.size();
			for (int i = 0; i < restarting.size(); i++) {
				if (restarting.get(i).nextRestartTime > record.nextRestartTime) {
					at = i;
					break;
				}
			}
			restarting.add(at, record);

			record.restartTime = now;
			record.remoteUp = false;
			plan.scheduled = true;
			plan.nextRestartTime = record.nextRestartTime;
			// killServicesLocked 7957-7965: a scheduled record with nothing left to
			// deliver that is stop-if-killed only restarts for its connections.
			plan.droppedStart = canStopIfKilledLocked(record, false);
		}
		return plan;
	}

	/** AOSP {@code ServiceRecord.canStopIfKilled} (ServiceRecord.java 1353-1358), without short-FGS. */
	private static boolean canStopIfKilledLocked(ServiceRecord record, boolean startCanceled) {
		return record.started && (record.stopIfKilled || startCanceled) && record.pendingStarts.isEmpty();
	}

	/**
	 * Takes {@code record} off the restarting list because it is being brought up
	 * again or torn down (AOSP bringUpServiceLocked 6194 / unscheduleServiceRestartLocked
	 * 6065-6082). The restart counters are kept, so a restart that keeps failing
	 * still climbs the ladder; a torn-down record is discarded with them.
	 */
	public void clearRestarting(ServiceRecord record) {
		synchronized (lock) {
			restarting.remove(record);
		}
	}
}
