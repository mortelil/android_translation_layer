/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Extraction of the ATLLoadedApp service block (base ab7345cf: resolveServiceComponent,
 * bringUpService, startService, ensureBindingIdentity, deliverBoundClients,
 * bindService, unbindService, postUnbindWork, unbindInstanceContextClients,
 * stopService, stopServiceFromSelf), moved without change of behavior except
 * for service placement resolution, the captured onStartCommand() result and
 * delivery of binders through ATLServiceDispatcher.
 *
 * The start delivery bookkeeping and the restart constants follow AOSP
 * frameworks-base android-17.0.0_r1 (94b4c163), services/core/java/com/android/
 * server/am/ActiveServices.java (sendServiceArgsLocked, serviceDoneExecutingLocked,
 * scheduleServiceRestartLocked) and ActivityManagerConstants.java 222-230.
 * Algorithms are extracted, not imported wholesale.
 */
package android.atl;

import android.app.ActivityThread;
import android.app.ContextImpl;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageParser;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * Service policy of one {@link ATLLoadedApp}: the controller decides what a
 * {@code startService}/{@code bindService}/{@code unbindService}/
 * {@code stopService}/{@code stopSelf} request does and performs the lifecycle
 * callbacks, while {@link ATLServiceRegistry} only holds the data. ATLLoadedApp
 * constructs one controller eagerly and its public service methods are a thin
 * bridge onto it.
 *
 * <p>All orchestration runs on the main looper: the public entry points record
 * their request in the registry synchronously (so the caller observes the new
 * state at once) and post the application callbacks, in call order. No
 * application callback ({@code onCreate}/{@code onStartCommand}/{@code onBind}/
 * {@code onUnbind}/{@code onRebind}/{@code onDestroy}, any
 * {@link ServiceConnection} callback) is ever invoked while a registry lock is
 * held: the controller only calls the registry for state, and calls the
 * application afterwards.
 *
 * <p>Client connections are delivered through one {@link ATLServiceDispatcher}
 * per (Context, ServiceConnection), kept in {@link #dispatchers}. A dispatcher
 * is created by the first bind of that pair and forgotten (and dropped from the
 * map) when the pair is unbound, either by an explicit {@code unbindService} or
 * because the Service that issued the bind from its own context was destroyed.
 * The map is not weak: it is scoped to the lifetime of its ATLLoadedApp, as
 * AOSP's {@code LoadedApk.mServices} is, and an entry for a registration that is
 * never unbound lives as long as the registry's own client entry does.
 *
 * <p>Placement: {@link #resolvePlacement} decides per manifest service whether it
 * runs in the process this controller runs in -- the loader's, or a service
 * child's own ({@code ATL_SERVICE_PROCESS}) -- (null) or in a process of its own. A remote
 * placement needs a process backend: with none installed (a tree without the
 * lane-D host) {@link #placementRemoteProcess} refuses it exactly as before, and
 * with the fake backend installed (env {@code ATL_PROCESS_BACKEND=fake}) the
 * child-side lifecycle runs through {@link ATLProcessBackend} operations on
 * the main looper, in post order with every other op.
 */
public final class ATLServiceController {
	private static final ArrayList<ATLServiceController> controllers = new ArrayList<>();
	private static final String TAG = "ATLLoadedApp";

	// Restart tuning, defaults from AOSP ActivityManagerConstants 222-230 (carried by
	// ATLServiceRegistry.RestartConstants); each may be overridden through the
	// environment for tests only.
	static final String ENV_RESTART_DURATION_MS = "ATL_SERVICE_RESTART_DURATION_MS";
	static final String ENV_RESTART_DURATION_FACTOR = "ATL_SERVICE_RESTART_DURATION_FACTOR";
	static final String ENV_RESET_RUN_DURATION_MS = "ATL_SERVICE_RESET_RUN_DURATION_MS";
	static final String ENV_MIN_RESTART_TIME_BETWEEN_MS = "ATL_SERVICE_MIN_RESTART_TIME_BETWEEN_MS";
	static final String ENV_BOUND_CRASH_RESTART_DURATION_MS = "ATL_SERVICE_BOUND_CRASH_RESTART_DURATION_MS";
	static final String ENV_BOUND_CRASH_MAX_RETRY = "ATL_SERVICE_BOUND_CRASH_MAX_RETRY";
	static final String ENV_MIN_CRASH_INTERVAL_MS = "ATL_SERVICE_MIN_CRASH_INTERVAL_MS";

	/** The result of resolving an intent against the manifest: the component and where it runs. */
	private static final class Resolved {
		final ComponentName component;
		/** The process the component is placed in, or null for the loader's own process. */
		final String remoteProcess;

		Resolved(ComponentName component, String remoteProcess) {
			this.component = component;
			this.remoteProcess = remoteProcess;
		}
	}

	private final ATLLoadedApp app;
	private final ATLServiceRegistry service_registry = new ATLServiceRegistry();
	/**
	 * Restart tuning built from the ATL_SERVICE_* environment. Declared with the
	 * controller but not read yet: the restart policy that uses it arrives with the
	 * process backend wiring; nothing in the local paths consults it.
	 */
	final ATLServiceRegistry.RestartConstants restart_constants = restartConstantsFromEnv();
	/**
	 * Client registrations, keyed by the context the bind was issued from and the
	 * connection that was passed. Guarded by itself; a dispatcher is only ever
	 * called (doConnected/doForget) outside this monitor.
	 */
	private final IdentityHashMap<Context, IdentityHashMap<ServiceConnection, ATLServiceDispatcher>> dispatchers =
	    new IdentityHashMap<>();
	/**
	 * Start items taken from a record's pendingStarts whose delivery op has not run
	 * yet. The registry hands out its whole pending queue at once
	 * ({@link ATLServiceRegistry#takePendingStarts}), but every startService() call
	 * posts its own op and each op must deliver exactly one onStartCommand() in post
	 * order, so the surplus waits here. Touched only on the main looper.
	 */
	private final IdentityHashMap<ATLServiceRegistry.ServiceRecord, ArrayDeque<ATLServiceRegistry.StartItem>> stagedStarts =
	    new IdentityHashMap<>();

	/** Env selecting the (test) process backend; only "fake" exists so far. */
	static final String ENV_PROCESS_BACKEND = "ATL_PROCESS_BACKEND";
	/** Env marking this loader as a service child (PR31 ATL_SERVICE_PROCESS refusal). */
	static final String ENV_SERVICE_PROCESS = "ATL_SERVICE_PROCESS";
	/** Env the lane-D transport sets in a spawned service child (see ATLProcessHost). */
	static final String ENV_SERVICE_HOST = "ATL_SERVICE_HOST";
	/** The lane-D transport host: combined tree only, hence the reflective lookup. */
	private static final String ATL_PROCESS_HOST_CLASS = "android.atl.ATLProcessHost";
	/**
	 * The lane-D service-child runtime: the receiver of a hosted instance's
	 * STOP_SELF report, looked up reflectively for the same reason as
	 * {@link #ATL_PROCESS_HOST_CLASS} (combined tree only).
	 */
	private static final String ATL_CHILD_HOST_CLASS = "android.atl.ChildHost";
	private static final String ATL_CHILD_HOST_REPORT_STOP_SELF = "reportStopSelf";

	/**
	 * Posts policy work to the main looper. Created on first use, never in a field
	 * initializer: the controller is constructed from ATLLoadedApp's constructor, and
	 * that runs during android.content.Context's static initialization
	 * (Context.<clinit> -> ATLLoadedApp.getPrimaryApplication()), i.e. from JNI_OnLoad,
	 * before the launcher has prepared the main looper -- a Handler built there would
	 * dereference a null looper and fail Context's class initialization.
	 */
	private Handler main;

	private Handler main() {
		synchronized (this) {
			if (main == null)
				main = new Handler(Looper.getMainLooper());
			return main;
		}
	}
	/**
	 * The installed process backend for remote services, or null: remote
	 * placements are refused then, exactly as before the backend existed.
	 */
	private final ATLProcessBackend processBackend;
	/**
	 * Live remote instances (fake backend; populated through
	 * {@link ATLProcessBackend.ChildEventListener#onInstanceHosted}): the
	 * controller resolves a hosted instance's stopSelf() back to its record
	 * (S09), because a remote record's own {@code instance} stays null.
	 */
	private final IdentityHashMap<Service, ATLServiceRegistry.ServiceRecord> remoteInstances =
	    new IdentityHashMap<>();
	/**
	 * The last death generation reported per process name (coordinator ruling:
	 * a backend callback captured for G &lt;= lastDiedGeneration[P] is stale).
	 * The per-record guard is {@link ATLServiceRegistry.ServiceRecord#generation},
	 * which {@link ATLServiceRegistry#forgetRemoteProcessState} bumps on death.
	 */
	private final ArrayMap<String, Integer> lastDiedGeneration = new ArrayMap<>();

	public ATLServiceController(ATLLoadedApp app) {
		this.app = app;
		this.processBackend = installProcessBackend();
		synchronized (controllers) { controllers.add(this); }
	}

	private static ATLServiceRegistry.RestartConstants restartConstantsFromEnv() {
		ATLServiceRegistry.RestartConstants c = new ATLServiceRegistry.RestartConstants();
		c.restartDurationMs = envLong(ENV_RESTART_DURATION_MS, c.restartDurationMs);
		c.restartDurationFactor = envLong(ENV_RESTART_DURATION_FACTOR, c.restartDurationFactor);
		c.resetRunDurationMs = envLong(ENV_RESET_RUN_DURATION_MS, c.resetRunDurationMs);
		c.minRestartTimeBetweenMs = envLong(ENV_MIN_RESTART_TIME_BETWEEN_MS, c.minRestartTimeBetweenMs);
		c.boundCrashRestartDurationMs = envLong(ENV_BOUND_CRASH_RESTART_DURATION_MS, c.boundCrashRestartDurationMs);
		c.boundCrashMaxRetry = (int)envLong(ENV_BOUND_CRASH_MAX_RETRY, c.boundCrashMaxRetry);
		c.minCrashIntervalMs = envLong(ENV_MIN_CRASH_INTERVAL_MS, c.minCrashIntervalMs);
		return c;
	}

	private static long envLong(String name, long defaultValue) {
		String value = System.getenv(name);
		if (value == null || value.isEmpty())
			return defaultValue;
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			Slog.w(TAG, "ignoring non-numeric " + name + "=" + value);
			return defaultValue;
		}
	}

	/**
	 * Resolves an intent to a service component DECLARED by this application.
	 * An unknown component (not declared in the manifest) resolves to null:
	 * startService must not report success for a service that cannot be found.
	 */
	public ComponentName resolveServiceComponent(Intent intent) {
		Resolved resolved = resolveService(intent);
		return resolved == null ? null : resolved.component;
	}

	/** {@link #resolveServiceComponent} together with the placement of the resolved service. */
	private Resolved resolveService(Intent intent) {
		java.util.List<android.content.pm.ResolveInfo> matches =
			ATLIntentResolver.queryPackage(app.pkg, intent, 0, true);
		if (matches.isEmpty()) return null;
		String name = matches.get(0).serviceInfo.name;
		for (PackageParser.Service service : app.pkg.services) {
			if (name.equals(service.className))
				return new Resolved(service.getComponentName(), resolvePlacement(service));
		}
		return null;
	}

	/**
	 * Where {@code service} runs: null for the process this controller runs in,
	 * otherwise the manifest-declared process. An isolated service is refused, and
	 * so is a service in another process until {@link #placementRemoteProcess}
	 * accepts it. The refusals keep the message the local-service probe's
	 * unsupported-remote check (SVC-014) expects.
	 *
	 * <p>The comparison is against the process that is RUNNING, not against the
	 * application's default process: a service child ({@code ATL_SERVICE_PROCESS},
	 * see ATLProcessHost/ChildHost) hosts the services declared for its own
	 * android:process, and a bind it issues to one of them is a bind to itself.
	 * AOSP resolves a service request to a process by the DECLARED name of the
	 * service and reuses that process when it is already up
	 * ({@code ActiveServices.bringUpServiceLocked}: {@code procName = r.processName}
	 * then {@code mAm.getProcessRecordLocked(procName, uid)},
	 * services/core/java/com/android/server/am/ActiveServices.java:6228-6266 in the
	 * pinned tree), so on Android the crash-helper child's own
	 * {@code bindService(CrashHelper)} lands on itself with no second process;
	 * comparing with the default process name instead made it look remote and,
	 * inside a child, refused it. Note the same-process case is decided here and
	 * not by {@link #placementRemoteProcess}: it is not a remote placement at all.
	 */
	private String resolvePlacement(PackageParser.Service service) {
		if ((service.info.flags & android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
			throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
		}
		String process = ATLProcessControl.componentProcess(app, service);
		if (app.pkg.packageName.equals(ActivityThread.currentPackageName())
		    && process.equals(ActivityThread.currentProcessName())) return null;
		return placementRemoteProcess(service, process);
	}

	/**
	 * SEAM for the process backend wiring: the single place that decides whether a
	 * service placed in {@code process} (neither the running process nor a null
	 * process) may run there. It returns the process name to place the record in,
	 * or throws when it may not. Split APKs are refused with the PR31 wording.
	 *
	 * <p>In a service child there are two cases. A real spawned child
	 * ({@code ATL_SERVICE_HOST}, set by the loader's spawn) reaches other
	 * processes through the loader: the request is forwarded over the control
	 * link and the loader performs it with its own backend, so the placement is
	 * allowed only when that forwarding backend is installed; without one the
	 * child stays explicitly unable to establish the process. A probe launch that
	 * merely claims {@code ATL_SERVICE_PROCESS} (the S14c gate) has no host and
	 * is refused with exactly the message the SVC-014 check grew up with, and a
	 * tree without the lane-D host keeps the generic unsupported-remote wording.
	 */
	private String placementRemoteProcess(PackageParser.Service service, String process) {
		String[] splits = app.pkg.applicationInfo.splitSourceDirs;
		if (splits != null && splits.length > 0)
			throw new UnsupportedOperationException("Remote services in split APKs are not implemented");
		if (System.getenv(ENV_SERVICE_HOST) != null) {
			if (processBackend == null)
				throw new UnsupportedOperationException("Service-to-other-process bindings are not implemented");
			return process;
		}
		if (System.getenv(ENV_SERVICE_PROCESS) != null)
			throw new UnsupportedOperationException("Service-to-other-process bindings are not implemented");
		if (processBackend == null)
			throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
		return process;
	}

	/**
	 * Brings the service for {@code record} up on the calling thread (the main
	 * looper): instantiates and attaches it on first use, then runs onCreate().
	 * Returns null when the record was stopped before this delivered start landed.
	 */
	private Service bringUpService(ATLServiceRegistry.ServiceRecord record) throws ReflectiveOperationException {
		Service service = record.instance;
		if (service != null)
			return service;
		Class<? extends Service> cls = app.loadClass(record.className).asSubclass(Service.class);
		service = cls.getConstructor().newInstance();
		ContextImpl serviceContext = new ContextImpl(
		    app.createDefaultResources(), app, app.pkg.applicationInfo.theme);
		service.attachBaseContext(serviceContext);
		// Publish the instance before onCreate() so a re-entrant call sees it.
		// The context is remembered with it: bindings a Service issues from its own
		// context belong to this instance and are unbound when it is destroyed
		// (LoadedApk.removeContextRegistrations with what="Service").
		record.instanceContext = serviceContext;
		record.instance = service;
		service.onCreate();
		return service;
	}

	/**
	 * startService: allocates this call's start ID (1 for the first start of a
	 * component, then monotonic per component like AOSP's ServiceRecord) and
	 * delivers onCreate() (once) then onStartCommand() on the main looper.
	 * Returns the resolved component, or null when the component is unresolvable.
	 */
	public ComponentName startService(final Intent intent) {
		final Resolved resolved = this.resolveService(intent);
		if (resolved == null) {
			Slog.w(TAG, "startService: no matching service found for intent: " + intent);
			return null;
		}
		if (resolved.remoteProcess != null) {
			return startRemoteService(resolved, intent);
		}
		final ComponentName component = resolved.component;
		final ATLServiceRegistry.ServiceRecord record =
		    service_registry.allocateStart(component, intent, resolved.remoteProcess);

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				// Taken first, so an op that fails to bring the service up still
				// consumes the start item it was posted for.
				ATLServiceRegistry.StartItem item = takeStartItem(record);
				try {
					Service service = bringUpService(record);
					// A client that bound without AUTO_CREATE is delivered its binder
					// as soon as the service comes up through startService().
					deliverBoundClients(record);
					if (item != null)
						deliverStart(record, service, item);
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "startService: failed to start service " + component.getClassName(), e);
				}
			}
		});

		return component;
	}

	/**
	 * The start item this startService() op delivers: the oldest one not delivered
	 * yet. Each op consumes exactly one item, so the items reach onStartCommand()
	 * in start ID order, one per op, however the registry batches its queue.
	 * Returns null when AOSP's batch rule skips the item (a null-intent item in a
	 * batch of several; sendServiceArgsLocked 6665-6670). Main looper only.
	 */
	private ATLServiceRegistry.StartItem takeStartItem(ATLServiceRegistry.ServiceRecord record) {
		ArrayDeque<ATLServiceRegistry.StartItem> staged = stagedStarts.get(record);
		if (staged == null) {
			staged = new ArrayDeque<>();
			stagedStarts.put(record, staged);
		}
		staged.addAll(service_registry.takePendingStarts(record));
		int batchSize = staged.size();
		ATLServiceRegistry.StartItem item = staged.pollFirst();
		if (staged.isEmpty())
			stagedStarts.remove(record);
		if (item == null || service_registry.skipNullIntent(item, batchSize))
			return null;
		return item;
	}

	/**
	 * Hands {@code item} to onStartCommand() and records what it returned
	 * (AOSP sendServiceArgsLocked + serviceDoneExecutingLocked). The result is
	 * bookkeeping only here: a value AOSP rejects is logged, not thrown into the
	 * application's main looper.
	 */
	private void deliverStart(ATLServiceRegistry.ServiceRecord record, Service service,
	                          ATLServiceRegistry.StartItem item) {
		service_registry.markStartDelivered(record, item, SystemClock.uptimeMillis());
		int flags = service_registry.startFlagsFor(item);
		int res = service.onStartCommand(item.intent, flags, item.id);
		try {
			service_registry.onStartCommandResult(record, item.id, res);
		} catch (IllegalArgumentException e) {
			Slog.w(TAG, "startService: " + record.className + " returned an unknown start result", e);
		}
	}

	/**
	 * Resolves the binder for {@code intent}'s binding identity by running
	 * {@code onBind()} once per identity on the live instance (Android keys bind
	 * requests by {@code Intent.FilterComparison}: extras are ignored, a different
	 * action is a new identity and re-runs {@code onBind()} on the same
	 * instance). The returned binder is cached on the record.
	 *
	 * <p>Returns true when the identity was left rebind-pending by a previous
	 * {@code onUnbind()} that returned true: the caller then delivers
	 * {@code onRebind()} (after {@code onServiceConnected()}) and {@code onBind()}
	 * is not run again. The pending flag is consumed here, so a second bind of the
	 * same identity runs neither callback.
	 */
	private boolean ensureBindingIdentity(ATLServiceRegistry.ServiceRecord record, Intent intent) {
		if (record.instance == null || intent == null)
			return false;
		if (service_registry.hasBindingIdentity(record, intent))
			return false;
		service_registry.addBindingIdentity(record, intent);
		if (service_registry.consumeRebindPending(record, intent))
			return true;
		record.binder = record.instance.onBind(intent);
		service_registry.cacheBinder(record, intent, record.binder);
		record.binderResolved = true;
		return false;
	}

	/**
	 * Delivers the binder to every processed-but-undelivered client of
	 * {@code record} in registration order, resolving each pending client's
	 * binding identity first. Each context's dispatcher deduplicates an unchanged
	 * binder; a replacement binder disconnects the old one before connecting the new one. An identity whose previous {@code onUnbind()} returned true
	 * gets {@code onRebind()} after the connect callback instead of a fresh
	 * {@code onBind()}. Must be called on the main looper, never while the registry
	 * lock is held.
	 */
	private void deliverBoundClients(ATLServiceRegistry.ServiceRecord record) {
		if (record.instance == null)
			return;
		ArrayList<ATLServiceRegistry.BoundClient> clients = service_registry.bindingsOf(record);
		for (ATLServiceRegistry.BoundClient client : clients) {
			if (client.requested && !client.delivered && ensureBindingIdentity(record, client.intent))
				client.rebind = true;
		}
		for (ATLServiceRegistry.BoundClient client : clients) {
			if (!client.requested || client.delivered)
				continue;
			client.delivered = true;
			IBinder binder = service_registry.binderFor(record, client.intent);
			// A registration unbound since the snapshot has no dispatcher any more:
			// it is forgotten, so it gets no late connect.
			ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
			if (dispatcher != null)
				dispatcher.doConnected(record.component, binder, false);
			// Android delivers onRebind() right after the client is connected;
			// an onUnbind() == true for this identity armed it.
			if (client.rebind && record.instance != null)
				record.instance.onRebind(client.intent);
		}
	}

	/**
	 * bindService: resolves the component, registers the client and delivers the
	 * service's binder on the main looper. A bind with
	 * {@link Context#BIND_AUTO_CREATE} brings the instance up (onCreate) and runs
	 * onBind() once; a bind without it is only delivered once some later
	 * AUTO_CREATE bind (or startService) creates the instance. All clients of the
	 * record are delivered in registration order, so a pending client is notified
	 * before the client whose bind created the service. Because queued work is
	 * applied in post order, the op still runs onBind() for the identity it
	 * captured even when a later-queued unbind already removed the connection.
	 */
	public ComponentName bindService(final Context bindContext, final Intent intent,
	                                 final ServiceConnection serviceConnection, final int flags) {
		if (serviceConnection == null) {
			return startService(intent);
		}
		final Resolved resolved = this.resolveService(intent);
		if (resolved == null) {
			Slog.w(TAG, "bindService: no matching service found for intent: " + intent);
			return null;
		}
		final ComponentName component = resolved.component;
		final boolean autoCreate = (flags & Context.BIND_AUTO_CREATE) != 0;
		// PR31 parity: a bind that may not create the service may not spawn a
		// remote process for it either. Synchronous, like the placement refusals.
		if (resolved.remoteProcess != null && !autoCreate
		    && !processBackend.isProcessUp(resolved.remoteProcess)) {
			throw new UnsupportedOperationException(
			    "Non-auto-create binding to an unstarted remote process is not implemented");
		}
		final ATLServiceRegistry.ServiceRecord record =
		    service_registry.allocateBind(component, resolved.remoteProcess);
		// The dispatcher exists before the client is registered, so no delivery can
		// find a registered client without one.
		getServiceDispatcher(bindContext, serviceConnection);
		final ATLServiceRegistry.BoundClient client =
		    service_registry.registerBinding(record, serviceConnection, intent, bindContext, flags);

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				// The bind op has now been processed on the main looper: mark it so
				// deliverBoundClients() sees it. A bind without BIND_AUTO_CREATE stays
				// pending (requested but not delivered) until a later op brings the
				// instance up.
				client.requested = true;
				try {
					if (record.isRemote()) {
						deliverRemoteBinders(record, record.generation);
						return;
					}
					if (record.instance == null) {
						if (!autoCreate)
							return;
						if (bringUpService(record) == null)
							return;
					}
					deliverBoundClients(record);
					// Android applies the queued bind work in post order: this op has
					// brought the instance up and must run onBind() for the binding
					// identity it captured, even when an unbind queued after this bind
					// (but processed before its op) already removed the connection from
					// the registry. The unbind op owns the removal/onUnbind()/onDestroy()
					// for that connection, so only the identity resolution happens here.
					// An identity whose onUnbind() returned true is answered with
					// onRebind() instead of onBind().
					//
					// Skip it when this op's own client was already delivered above: a
					// reentrant unbindService() issued from onServiceConnected() removes
					// the identity during deliverBoundClients(), and re-resolving it here
					// would re-run onBind() on an instance the queued unbind op is about
					// to destroy (Android delivers no such onBind; SVC-013 13b).
					if (!client.delivered && ensureBindingIdentity(record, intent) && record.instance != null)
						record.instance.onRebind(intent);
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "bindService: failed to bind service " + component.getClassName(), e);
				}
			}
		});

		return component;
	}

	/** Context registrations belong to the caller, even when the target is another APK. */
	public static void unbindFromContext(Context context, ServiceConnection connection) {
		ArrayList<ATLServiceController> snapshot;
		synchronized (controllers) { snapshot = new ArrayList<>(controllers); }
		boolean found = false;
		for (ATLServiceController controller : snapshot) {
			if (controller.peekServiceDispatcher(context, connection) == null) continue;
			controller.unbindService(context, connection);
			found = true;
		}
		if (!found) throw new IllegalArgumentException("Service not registered: " + connection);
	}

	/**
	 * unbindService: removes the client from the registry and posts one
	 * {@code onUnbind()} per binding identity whose last client it removed, even
	 * when the instance survives for other clients or a startService() request;
	 * {@code onDestroy()} is posted only when the unbind left the service neither
	 * started nor bound (the destroy flag). A true {@code onUnbind()} return arms
	 * {@code onRebind()} for the next bind of that identity. Unknown connections
	 * throw, matching Android. onServiceDisconnected() is never called for an
	 * ordinary unbind. The registration's dispatcher is forgotten, so a connect
	 * still in flight for it is dropped.
	 */
	public void unbindService(Context context, ServiceConnection serviceConnection) {
		ArrayList<ATLServiceRegistry.UnbindResult> results = service_registry.unbind(context, serviceConnection);
		forgetServiceDispatcher(context, serviceConnection);
		for (ATLServiceRegistry.UnbindResult result : results) {
			if (result.record.isRemote())
				postRemoteUnbindWork(result.record, result.intents, result.destroy);
			else
				postUnbindWork(result.record, result.intents, result.destroy);
		}
	}

	/**
	 * Posts the main-looper work of a client removal: one {@code onUnbind()} per
	 * removed binding identity (deduped), then {@code onDestroy()} when the unbind
	 * left the service neither started nor bound, and finally, for a destroyed
	 * instance, the cleanup of the bindings it had issued from its own context.
	 */
	private void postUnbindWork(final ATLServiceRegistry.ServiceRecord record,
	                            final ArrayList<Intent> intents, final boolean destroy) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				Service service = record.instance;
				if (service == null)
					return;
				// Android delivers one onUnbind() per removed binding identity
				// (extras-deduped) as soon as that identity's last client is gone,
				// then destroys the instance only when nothing keeps it alive.
				for (Intent intent : intents) {
					if (service.onUnbind(intent))
						service_registry.markRebindPending(record, intent);
				}
				if (destroy) {
					service.onDestroy();
					unbindInstanceContextClients(record);
				}
			}
		});
	}

	/**
	 * LoadedApk.removeContextRegistrations(context, who, "Service"), the cleanup
	 * ActivityThread.handleStopService() schedules once a Service is destroyed: a
	 * dead instance can no longer hold bindings, so every registration issued from
	 * that instance's context is unbound -- removed from the record that holds it,
	 * which is torn down when nothing else keeps it alive -- and forgotten as a
	 * client registration. This is why a bind a service issues re-entrantly from
	 * its own {@code onUnbind()} disappears again once that instance's teardown
	 * messages have run, and why a later {@code unbindService()} of that
	 * connection from any other context throws "Service not registered".
	 */
	private void unbindInstanceContextClients(ATLServiceRegistry.ServiceRecord record) {
		final Context context = record.instanceContext;
		if (context == null)
			return;
		ArrayList<ATLServiceController> snapshot;
		synchronized (controllers) { snapshot = new ArrayList<>(controllers); }
		for (ATLServiceController controller : snapshot) {
			ArrayList<ATLServiceRegistry.UnbindResult> results = controller.service_registry.unbindContext(context);
			controller.forgetContextDispatchers(context);
			for (ATLServiceRegistry.UnbindResult result : results) {
				if (result.record.isRemote()) controller.postRemoteUnbindWork(result.record, result.intents, result.destroy);
				else controller.postUnbindWork(result.record, result.intents, result.destroy);
			}
		}
	}

	/**
	 * stopService: detaches the record synchronously (so the returned boolean and
	 * a second stopService observe the stopped state immediately) and posts
	 * onDestroy() to the main looper, matching Android where onDestroy lands after
	 * the call has returned. The instance is read at op-run time, not at call
	 * time: a startService() whose op has not run yet still creates its instance
	 * and must be destroyed by the matching stopService().
	 */
	public boolean stopService(Intent intent) {
		ComponentName component = intent.getComponent();
		if (component == null) {
			Slog.w(TAG, "stopService: no component in intent: " + intent);
			return false;
		}
		final ATLServiceRegistry.StopResult result = service_registry.stop(component);
		if (!result.running)
			return false;
		if (result.keptBound)
			return true;
		final ATLServiceRegistry.ServiceRecord record = result.record;
		if (record.isRemote()) {
			teardownRemoteInstance(record);
			return true;
		}
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				Service service = record.instance;
				if (service == null)
					return;
				service.onDestroy();
				unbindInstanceContextClients(record);
			}
		});
		return true;
	}

	/**
	 * stopSelf()/stopSelf(int)/stopSelfResult(int) entry point for a live
	 * instance. {@code startId < 0} (the stopSelf() form) stops unconditionally;
	 * a non-negative id must equal the record's last start ID, otherwise an
	 * older start ID must not stop newer work. The registry entry is removed
	 * before returning, and onDestroy() is posted to the main looper so it runs
	 * after the current onStartCommand() returns rather than re-entrantly inside
	 * it. Returns true when the service was stopped.
	 *
	 * <p>In a service child -- a process the loader spawned for a remote record --
	 * the instance belongs to no record of this controller and this controller is
	 * not the policy authority: the call is reported to the loader over the existing
	 * STOP_SELF receiver and nothing is stopped or unregistered here (AOSP: the
	 * child's stopSelf only marks the AMS-side record, and the teardown that brings
	 * the service down is what drives the child's onDestroy). The return is the
	 * loader's own decision on that report -- an older start ID must not stop newer
	 * work and is refused there (false) -- and a report that cannot be delivered is
	 * false too: a broken control link must never look like a successful stop.
	 */
	public boolean stopServiceFromSelf(Service service, int startId) {
		ATLServiceRegistry.ServiceRecord record = service_registry.findByInstance(service);
		if (record == null) {
			// A remote instance is hosted by the process backend, not by the
			// record itself: route it through the hosted-instance map (S09).
			record = remoteInstances.get(service);
			if (record == null) {
				// A service child holds no record of its own: the instance was created
				// by the child runtime for a remote record and the loader is the only
				// policy authority (a child never decides a stop). Report the call over
				// the existing STOP_SELF receiver and stop nothing here; the child stays
				// alive until the loader's teardown (DESTROY) arrives, which drives
				// onDestroy() exactly as AOSP's bringDownServiceLocked does.
				if (isServiceChild()) {
					return reportStopSelfToLoader(service.getClass().getName(), startId);
				}
				// Not started / never started, or already stopped (a dead
				// instance's stopSelf is a no-op).
				return false;
			}
		}
		if (record.isRemote())
			return stopServiceFromSelfRemote(record, startId);
		ATLServiceRegistry.StopResult result = service_registry.stopSelf(record, startId);
		if (!result.running) return false;
		if (result.keptBound) return true;
		final Service instance = record.instance;
		final ATLServiceRegistry.ServiceRecord stopped = record;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				instance.onDestroy();
				unbindInstanceContextClients(stopped);
			}
		});
		return true;
	}

	/**
	 * True when this process is a service child (spawned by the loader for a remote
	 * record): the marker {@link #selectProcessBackend} already uses to keep a child
	 * from hosting anything itself. A child's controller holds no record for the
	 * instances the child runtime hosts, so their stopSelf() calls must be reported to
	 * the loader rather than decided here.
	 */
	private static boolean isServiceChild() {
		return System.getenv(ENV_SERVICE_HOST) != null;
	}

	/**
	 * Reports a hosted instance's stopSelf*() to the loader through the lane-D
	 * service-child runtime's STOP_SELF sender, looked up reflectively for the same
	 * reason as {@link #ATL_PROCESS_HOST_CLASS}: a direct reference would not build in
	 * a tree without the lane-D transport. The sender returns the loader's decision,
	 * which is what {@code Service.stopSelfResult(int)} must return; a report that
	 * cannot be delivered is false there, never a fabricated success. A missing
	 * receiver is loud, never a silent degradation -- this process is a service child,
	 * so the runtime it was spawned under must provide it.
	 */
	private static boolean reportStopSelfToLoader(String componentClass, int startId) {
		Object decision;
		try {
			decision = Class.forName(ATL_CHILD_HOST_CLASS)
			               .getMethod(ATL_CHILD_HOST_REPORT_STOP_SELF, String.class, int.class)
			               .invoke(null, componentClass, startId);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("service child without " + ATL_CHILD_HOST_CLASS, e);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(ATL_CHILD_HOST_CLASS + "." + ATL_CHILD_HOST_REPORT_STOP_SELF
			                                + " for " + componentClass + " startId=" + startId + " failed", e);
		}
		return Boolean.TRUE.equals(decision);
	}

	// ---------------------------------------------------------------------
	// Remote placement: process-backend wiring (fake backend until the lane-D
	// transport lands; DESIGN-BRIEF 5.3-5.6)
	// ---------------------------------------------------------------------

	/**
	 * Installs the process backend for remote services: the in-loader test fake
	 * (env {@code ATL_PROCESS_BACKEND=fake}) or the lane-D transport host, which
	 * exists only on the combined tree and is therefore looked up reflectively
	 * ({@link #ATL_PROCESS_HOST_CLASS}#get()) - a direct reference would not build
	 * in a tree without it. With no backend at all (the production default) remote
	 * services stay explicitly unsupported.
	 */
	private ATLProcessBackend installProcessBackend() {
		ATLProcessBackend backend = selectProcessBackend();
		if (backend == null)
			return null;
		backend.setProcessDeathListener(new ATLProcessBackend.ProcessDeathListener() {
			@Override
			public void onProcessDied(final String processName, final int generation) {
				// A death may be reported from any thread; all policy runs on
				// the main looper.
				main().post(new Runnable() {
					@Override
					public void run() {
						handleBackendProcessDeath(processName, generation);
					}
				});
			}
		});
		backend.setChildEventListener(new ATLProcessBackend.ChildEventListener() {
			@Override
			public boolean onStopSelf(final String processName, final String componentClass, final int startId) {
				// Real child hosts report stopSelf() over the wire and park in the
				// call; a fake (in-loader) instance routes through Service.stopSelf()
				// directly instead. The decision is the record's (stopServiceFromSelfRemote
				// below), computed here rather than posted: the record state it reads is
				// under the registry's own lock and the teardown it triggers is posted,
				// so this never blocks on the main looper -- which is what keeps a child's
				// stopSelfResult() from deadlocking against a parent->child call the main
				// looper is parked in. No record, or one that is not remote, is a refusal.
				ATLServiceRegistry.ServiceRecord record =
				    service_registry.findByComponent(new ComponentName(app.pkg.packageName, componentClass));
				return record != null && record.isRemote() && stopServiceFromSelfRemote(record, startId);
			}

			@Override
			public void onInstanceHosted(String processName, String componentClass, Service instance) {
				// Runs on the main looper, before the instance's first child-side
				// lifecycle call, so a re-entrant stopSelf() already finds its record.
				ATLServiceRegistry.ServiceRecord record =
				    service_registry.findByComponent(new ComponentName(app.pkg.packageName, componentClass));
				if (record != null && record.isRemote() && processName.equals(record.remoteProcess))
					remoteInstances.put(instance, record);
			}

			@Override
			public void onInstanceReleased(String processName, String componentClass, Service instance) {
				// The backend destroyed the instance (child onDestroy has run):
				// its context can no longer hold bindings.
				if (remoteInstances.remove(instance) != null)
					releaseInstanceRegistrations(instance);
			}
		});
		return backend;
	}

	/**
	 * The backend to install: the test fake, the service-child forwarding backend,
	 * the lane-D transport host, or null.
	 * Order: {@code ATL_PROCESS_BACKEND=fake} first (lane-B test seam), then the
	 * {@code ATL_SERVICE_HOST} marker (this process IS a service child: it hosts
	 * its own process's services but forwards every other declared process to the
	 * loader), then the reflective host lookup: a
	 * {@link ClassNotFoundException} means a tree without the lane-D host and
	 * leaves remote placements refused, any other reflective failure is loud
	 * (never a silent degradation), and a {@link LinkageError} from loading the
	 * host propagates untouched.
	 */
	private ATLProcessBackend selectProcessBackend() {
		if ("fake".equals(System.getenv(ENV_PROCESS_BACKEND)))
			return new ATLFakeProcessBackend(app);
		if (System.getenv(ENV_SERVICE_HOST) != null) {
			// This process IS a service child (spawned by the loader's transport):
			// it hosts services of its own process but cannot spawn another one, so
			// a component of another declared process is forwarded to the loader
			// (ATLProcessControl SVC_*). The backend resolves the control link when
			// an op runs, not now: this constructor runs during Context's static
			// initialization, before ChildHost starts the link.
			return ATLForwardingProcessBackend.get(app);
		}
		final Object host;
		try {
			host = Class.forName(ATL_PROCESS_HOST_CLASS).getMethod("get", ATLLoadedApp.class).invoke(null, app);
		} catch (ClassNotFoundException e) {
			// No lane-D host in this tree: remote services stay refused.
			return null;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(ATL_PROCESS_HOST_CLASS + ".get() failed", e);
		}
		return host == null ? null : (ATLProcessBackend)host;
	}

	/**
	 * Authoritative stop from a hosted remote instance (S09): the registry
	 * decides whether the start ID may stop the record and whether bound
	 * clients keep it alive; the child-side instance is destroyed through the
	 * backend only when the record is really stopped. Returns the decision the
	 * child's {@code Service.stopSelfResult(int)} reports: false when the
	 * registry refused (a stale start ID or no record at all), true when the
	 * record was brought down -- also when bound clients keep it running, as in
	 * AOSP's {@code stopServiceTokenLocked}, which returns true after
	 * {@code bringDownServiceIfNeededLocked} however that leaves the record.
	 *
	 * <p>Runs wherever the report arrives: in-loader (a fake backend's instance
	 * calls stopSelf() on its own thread) or on the transport worker that
	 * received the child's STOP_SELF. Only the registry's lock-guarded state is
	 * read here and the teardown is posted to the main looper, so this never
	 * waits on the main looper and a parked child cannot deadlock it.
	 */
	private boolean stopServiceFromSelfRemote(ATLServiceRegistry.ServiceRecord record, int startId) {
		ATLServiceRegistry.StopResult result = service_registry.stopSelf(record, startId);
		if (!result.running)
			return false;
		if (result.keptBound)
			return true;
		teardownRemoteInstance(result.record);
		return true;
	}

	/**
	 * Destroys the child-side instance of a stopped remote record: onDestroy()
	 * runs in the child (backend op) and the instance's own registrations are
	 * released when the backend reports the instance gone.
	 */
	private void teardownRemoteInstance(final ATLServiceRegistry.ServiceRecord record) {
		main().post(new Runnable() {
			@Override
			public void run() {
				processBackend.destroyService(record.remoteProcess, record.className);
			}
		});
	}

	/**
	 * LoadedApk.removeContextRegistrations for a destroyed REMOTE instance: the
	 * bindings it issued from its own context are unbound -- the target records
	 * are torn down when nothing else keeps them alive -- and its client
	 * registrations are forgotten.
	 */
	private void releaseInstanceRegistrations(Service instance) {
		Context context = instance.getBaseContext();
		if (context == null)
			return;
		ArrayList<ATLServiceRegistry.UnbindResult> results = service_registry.unbindContext(context);
		forgetContextDispatchers(context);
		for (ATLServiceRegistry.UnbindResult result : results) {
			if (result.record.isRemote())
				postRemoteUnbindWork(result.record, result.intents, result.destroy);
			else
				postUnbindWork(result.record, result.intents, result.destroy);
		}
	}

	/**
	 * startService for a service the manifest places in another process
	 * (S09/S10): the request is recorded synchronously like a local one, then
	 * the child's onCreate (through a pending bind, if any) and onStartCommand
	 * run as backend ops on the main looper, in post order with every other op.
	 */
	private ComponentName startRemoteService(final Resolved resolved, Intent intent) {
		final ATLServiceRegistry.ServiceRecord record =
		    service_registry.allocateStart(resolved.component, intent, resolved.remoteProcess);
		main().post(new Runnable() {
			@Override
			public void run() {
				if (!service_registry.isLive(record))
					return;
				final int generation = record.generation;
				// A started and bound service delivers its binder before
				// onStartCommand (local order parity).
				deliverRemoteBinders(record, generation);
				for (ATLServiceRegistry.StartItem item : drainStartItems(record))
					deliverRemoteStart(record, generation, item);
			}
		});
		return resolved.component;
	}

	/**
	 * All start items of {@code record} that must be delivered now: the staged
	 * surplus of the one-item-per-op local rule first (oldest), then everything
	 * newly pending, minus the items AOSP's batch rule skips. The restart path
	 * delivers every pending item, not one per op.
	 */
	private ArrayList<ATLServiceRegistry.StartItem> drainStartItems(ATLServiceRegistry.ServiceRecord record) {
		ArrayList<ATLServiceRegistry.StartItem> taken = service_registry.takePendingStarts(record);
		ArrayDeque<ATLServiceRegistry.StartItem> staged = stagedStarts.remove(record);
		if (staged != null)
			taken.addAll(0, staged);
		ArrayList<ATLServiceRegistry.StartItem> deliver = new ArrayList<>();
		for (ATLServiceRegistry.StartItem item : taken) {
			if (!service_registry.skipNullIntent(item, taken.size()))
				deliver.add(item);
		}
		return deliver;
	}

	/**
	 * Delivers one start item to the child: the item is marked delivered at
	 * send time (AOSP stamps it in sendServiceArgsLocked, so a kill in flight
	 * counts as delivered and the restart plan owns it), the redelivery flags
	 * are computed from the R13 bookkeeping here in the loader, and the child's
	 * onStartCommand() return is applied when it arrives -- unless the hosting
	 * process died in between: a callback captured for an earlier generation is
	 * stale and is ignored (generation guard).
	 */
	private void deliverRemoteStart(final ATLServiceRegistry.ServiceRecord record, final int generation,
	                                final ATLServiceRegistry.StartItem item) {
		service_registry.markStartDelivered(record, item, SystemClock.uptimeMillis());
		final int flags = service_registry.startFlagsFor(item);
		processBackend.startService(record.remoteProcess, record.className, item.intent, flags, item.id,
		    new ATLProcessBackend.Callback<Integer>() {
			    @Override
			    public void onResult(Integer res) {
				    if (record.generation != generation || !service_registry.isLive(record))
					    return;
				    try {
					    service_registry.onStartCommandResult(record, item.id, res);
				    } catch (IllegalArgumentException e) {
					    Slog.w(TAG, "startService: " + record.className + " returned an unknown start result", e);
				    }
			    }

			    @Override
			    public void onHostUnavailable() {
				    handleRemoteSpawnFailure(record);
			    }
		    });
	}

	/**
	 * Resolves and delivers the binder of every processed-but-undelivered client
	 * of a remote record: an AUTO_CREATE bind, a bind left pending by an earlier
	 * op, or the rebind after a restart. The child runs onBind() once per
	 * binding identity (filterEquals, like the local ensureBindingIdentity); an
	 * identity armed by an onUnbind() == true gets the child's onRebind() and
	 * the cached binder; a null binder reports onNullBinding() through the
	 * dispatcher. Callbacks are generation-guarded; a spawn failure reports
	 * onServiceDisconnected() to the undelivered client (DO_SPAWN_FAILURE
	 * ruling) -- never onNullBinding(), never a false connect.
	 */
	private void deliverRemoteBinders(final ATLServiceRegistry.ServiceRecord record, final int generation) {
		ArrayList<ATLServiceRegistry.BoundClient> clients = service_registry.bindingsOf(record);
		for (final ATLServiceRegistry.BoundClient client : clients) {
			if (!client.requested || client.delivered)
				continue;
			final Intent intent = client.intent;
			ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
			if (dispatcher == null)
				continue; // unbound meanwhile: forgotten, no late connect
			if (!service_registry.hasBindingIdentity(record, intent)) {
				service_registry.addBindingIdentity(record, intent);
				if (service_registry.consumeRebindPending(record, intent)) {
					// The child survived the unbind that armed the rebind:
					// onRebind() there, then the binder it already returned.
					processBackend.rebindService(record.remoteProcess, record.className, intent);
					client.delivered = true;
					dispatcher.doConnected(record.component, service_registry.binderFor(record, intent), false);
					continue;
				}
				processBackend.bindService(record.remoteProcess, record.className, intent, client.flags,
				    new ATLProcessBackend.Callback<IBinder>() {
					    @Override
					    public void onResult(IBinder binder) {
						    if (record.generation != generation || !service_registry.isLive(record))
							    return;
						    if (client.delivered)
							    return; // a second result for a binding already reconnected
						    service_registry.cacheBinder(record, intent, binder);
						    record.binderResolved = true;
						    client.delivered = true;
						    ATLServiceDispatcher d = peekServiceDispatcher(client.context, client.connection);
						    if (d != null)
							    d.doConnected(record.component, binder, false);
					    }

					    @Override
					    public void onHostUnavailable() {
						    handleRemoteSpawnFailure(record);
					    }

					    @Override
					    public void onBindingDied() {
						    if (record.generation != generation || !service_registry.isLive(record)) return;
						    service_registry.forgetRemoteBinding(record);
						    client.delivered = false;
						    ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
						    if (dispatcher != null) dispatcher.doConnected(record.component, null, true);
					    }

					    @Override
					    public void onHostDied() {
						    // The host of THIS binding went down after it delivered:
						    // forward the death, keep the binding (and this request's
						    // generation) so the reconnect result reconnects the client.
						    handleRemoteBindingDied(record);
					    }
				    });
				continue;
			}
			// A known identity: deliver what is cached for it -- a bind without
			// BIND_AUTO_CREATE waits for exactly this.
			if (service_registry.hasCachedBinder(record, intent)) {
				client.delivered = true;
				dispatcher.doConnected(record.component, service_registry.binderFor(record, intent), false);
			}
		}
	}

	/**
	 * The hosting process of {@code record} could not be spawned: the component
	 * never ran, so the record holds nothing a client can wait for. Undelivered
	 * clients learn their request died through onServiceDisconnected() (the
	 * approved DO_SPAWN_FAILURE deviation; AOSP silently drops the client),
	 * never through onNullBinding() and never through a false connect.
	 */
	private void handleRemoteSpawnFailure(ATLServiceRegistry.ServiceRecord record) {
		service_registry.failRemote(record);
		for (ATLServiceRegistry.BoundClient client : service_registry.bindingsOf(record)) {
			if (client.delivered)
				continue;
			ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
			if (dispatcher != null)
				dispatcher.doSpawnFailure(record.component);
		}
	}

	/**
	 * The host of a binding that had already delivered its binder disconnected
	 * (the loader's client saw {@code onServiceDisconnected()}/{@code
	 * onBindingDied()}): the child-side half of AOSP {@code onServiceDisconnected},
	 * which keeps the binding. Delivered clients are notified with the binder they
	 * hold and marked undelivered, so the late OK the loader sends when its own
	 * restart ladder reconnects this binding delivers {@code onServiceConnected()}
	 * again. The record's binder state is forgotten WITHOUT bumping its
	 * {@code generation}: the reconnect arrives as a result of the same forwarded
	 * request, whose generation guard must stay valid. Unlike
	 * {@link #handleBackendProcessDeath} (the child's own process-death path) no
	 * restart is scheduled: the loader owns the life of the declared process and
	 * has already run its restart ladder.
	 */
	private void handleRemoteBindingDied(ATLServiceRegistry.ServiceRecord record) {
		ArrayList<ATLServiceRegistry.BoundClient> clients = service_registry.bindingsOf(record);
		ArrayList<IBinder> binders = new ArrayList<>();
		for (ATLServiceRegistry.BoundClient client : clients) {
			ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
			binders.add(dispatcher == null ? null : dispatcher.binderOf(record.component));
		}
		service_registry.forgetRemoteBinding(record);
		for (int i = 0; i < clients.size(); i++) {
			ATLServiceRegistry.BoundClient client = clients.get(i);
			if (!client.delivered)
				continue;
			client.delivered = false;
			IBinder binder = binders.get(i);
			ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
			if (dispatcher != null && binder != null)
				dispatcher.doDeath(record.component, binder);
		}
	}

	/**
	 * Posted work of a client removal on a remote record: the child runs
	 * onUnbind() (arming onRebind() when it returns true), and a record the
	 * removal left neither started nor bound is destroyed in the child. Backend
	 * ops run in post order, so the child sees every onUnbind() before any
	 * onDestroy().
	 */
	private void postRemoteUnbindWork(final ATLServiceRegistry.ServiceRecord record,
	                                  final ArrayList<Intent> intents, final boolean destroy) {
		main().post(new Runnable() {
			@Override
			public void run() {
				for (final Intent intent : intents) {
					processBackend.unbindService(record.remoteProcess, record.className, intent,
					    new ATLProcessBackend.Callback<Boolean>() {
						    @Override
						    public void onResult(Boolean res) {
							    if (res != null && res)
								    service_registry.markRebindPending(record, intent);
						    }

						    @Override
						    public void onHostUnavailable() {
							    // The hosting process is gone: its state was
							    // already forgotten by the death path.
						    }
					    });
				}
				if (destroy)
					processBackend.destroyService(record.remoteProcess, record.className);
			}
		});
	}

	/** One killed record's per-connection state captured under the registry locks, delivered outside them. */
	private static final class RemoteDeathWork {
		ATLServiceRegistry.ServiceRecord record;
		final ArrayList<ATLServiceRegistry.BoundClient> clients = new ArrayList<>();
		final ArrayList<IBinder> binders = new ArrayList<>();
	}

	/**
	 * killServicesLocked analogue for a hosting process that died: ALL registry
	 * state first (hosted instances, binders and identities, crash count --
	 * never a callback under a lock), then, outside the locks: delivered
	 * clients get onServiceDisconnected() only (S12: a death is not a
	 * binding-died); a record past the crash cap is torn down
	 * (onServiceDisconnected + onBindingDied, no restart); everything else is
	 * restarted per the R13 ladder at the planned time.
	 */
	private void handleBackendProcessDeath(String processName, int generation) {
		lastDiedGeneration.put(processName, Integer.valueOf(generation));
		ArrayList<Service> deadInstances = new ArrayList<>();
		synchronized (remoteInstances) {
			for (java.util.Map.Entry<Service, ATLServiceRegistry.ServiceRecord> entry : remoteInstances.entrySet()) {
				if (processName.equals(entry.getValue().remoteProcess))
					deadInstances.add(entry.getKey());
			}
			for (Service instance : deadInstances)
				remoteInstances.remove(instance);
		}
		ArrayList<RemoteDeathWork> work = new ArrayList<>();
		for (ATLServiceRegistry.ServiceRecord record : service_registry.recordsOfProcess(processName)) {
			RemoteDeathWork w = new RemoteDeathWork();
			w.record = record;
			for (ATLServiceRegistry.BoundClient client : service_registry.bindingsOf(record)) {
				if (!client.delivered)
					continue;
				ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
				w.clients.add(client);
				// The binder each connection last reported, captured before the
				// forget below clears the record's cache: what doDeath() matches.
				w.binders.add(dispatcher == null ? null : dispatcher.binderOf(record.component));
			}
			service_registry.forgetRemoteProcessState(record);
			service_registry.incServiceCrashCount(record, SystemClock.uptimeMillis(), restart_constants);
			work.add(w);
		}
		for (RemoteDeathWork w : work) {
			for (int i = 0; i < w.clients.size(); i++) {
				ATLServiceRegistry.BoundClient client = w.clients.get(i);
				client.delivered = false;
				IBinder binder = w.binders.get(i);
				ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
				if (dispatcher != null && binder != null)
					dispatcher.doDeath(w.record.component, binder);
			}
			if (w.record.crashCount >= restart_constants.boundCrashMaxRetry) {
				teardownRemoteRecord(w.record);
				continue;
			}
			ATLServiceRegistry.RestartPlan plan =
			    service_registry.planRestart(w.record, true, SystemClock.uptimeMillis(), restart_constants);
			if (plan.droppedStart)
				service_registry.setStarted(w.record, false);
			if (!plan.scheduled) {
				// Nothing needs the service any more: bring it down for good.
				teardownRemoteRecord(w.record);
				continue;
			}
			final ATLServiceRegistry.ServiceRecord record = w.record;
			main().postAtTime(new Runnable() {
				@Override
				public void run() {
					restartRemoteNow(record);
				}
			}, plan.nextRestartTime);
		}
	}

	/**
	 * Brings a remote record down for good (crash cap reached, or nothing needs
	 * it): delivered clients get the torn-down-binding report —
	 * onServiceDisconnected() (when the death disconnect has not run yet)
	 * followed by onBindingDied() — and the record is failed so nothing waits on
	 * it. No restart is scheduled (S12).
	 */
	private void teardownRemoteRecord(ATLServiceRegistry.ServiceRecord record) {
		service_registry.clearRestarting(record);
		for (ATLServiceRegistry.BoundClient client : service_registry.bindingsOf(record)) {
			// AOSP ActiveServices.java:6797 iterates r.getConnections() and notifies
			// EVERY registered connection that the host went down, and
			// LoadedApk.ServiceDispatcher.doConnected (LoadedApk.java:2402-2459)
			// reports onBindingDied even when nothing was ever delivered. So this
			// deliberately does NOT gate on client.delivered: the death path has
			// already cleared that flag on the clients it disconnected, and gating on
			// it here made the torn-down-binding notice unreachable (S12).
			client.delivered = false;
			ATLServiceDispatcher dispatcher = peekServiceDispatcher(client.context, client.connection);
			if (dispatcher != null)
				dispatcher.doConnected(record.component, null, true);
		}
		service_registry.failRemote(record);
	}

	/**
	 * performServiceRestartLocked analogue, at the planned time: the hosting
	 * process is spawned again, auto-create clients are re-bound (the fresh
	 * binder of the new-generation instance reconnects them after the death
	 * disconnect), and the starts the restart plan kept are delivered — a
	 * sticky service's fabricated null item, and any pending redelivery with
	 * its START_FLAG_RETRY/START_FLAG_REDELIVERY flags from the R13
	 * bookkeeping (uniform pendingStarts delivery, local and remote).
	 */
	private void restartRemoteNow(final ATLServiceRegistry.ServiceRecord record) {
		if (!service_registry.isLive(record))
			return;
		if (!service_registry.restartNeeded(record)) {
			teardownRemoteRecord(record);
			return;
		}
		service_registry.clearRestarting(record);
		final int generation = record.generation;
		deliverRemoteBinders(record, generation);
		service_registry.stickyRestartItem(record);
		for (ATLServiceRegistry.StartItem item : drainStartItems(record))
			deliverRemoteStart(record, generation, item);
	}

	/** The registration's dispatcher, created by the first bind of the pair (AOSP LoadedApk.getServiceDispatcher). */
	private ATLServiceDispatcher getServiceDispatcher(Context context, ServiceConnection connection) {
		synchronized (dispatchers) {
			IdentityHashMap<ServiceConnection, ATLServiceDispatcher> forContext = dispatchers.get(context);
			if (forContext == null) {
				forContext = new IdentityHashMap<>();
				dispatchers.put(context, forContext);
			}
			ATLServiceDispatcher dispatcher = forContext.get(connection);
			if (dispatcher == null) {
				dispatcher = new ATLServiceDispatcher(context, connection, new Handler(Looper.getMainLooper()));
				forContext.put(connection, dispatcher);
			}
			return dispatcher;
		}
	}

	/** The registration's dispatcher, or null when the pair is not (or no longer) registered. */
	private ATLServiceDispatcher peekServiceDispatcher(Context context, ServiceConnection connection) {
		synchronized (dispatchers) {
			IdentityHashMap<ServiceConnection, ATLServiceDispatcher> forContext = dispatchers.get(context);
			return forContext == null ? null : forContext.get(connection);
		}
	}

	/** Drops and forgets the registration's dispatcher (AOSP LoadedApk.forgetServiceDispatcher). */
	private void forgetServiceDispatcher(Context context, ServiceConnection connection) {
		ATLServiceDispatcher dispatcher = null;
		synchronized (dispatchers) {
			IdentityHashMap<ServiceConnection, ATLServiceDispatcher> forContext = dispatchers.get(context);
			if (forContext != null) {
				dispatcher = forContext.remove(connection);
				if (forContext.isEmpty())
					dispatchers.remove(context);
			}
		}
		if (dispatcher != null)
			dispatcher.doForget();
	}

	/** Drops and forgets every dispatcher registered from {@code context} (LoadedApk.removeContextRegistrations). */
	private void forgetContextDispatchers(Context context) {
		IdentityHashMap<ServiceConnection, ATLServiceDispatcher> forContext;
		synchronized (dispatchers) {
			forContext = dispatchers.remove(context);
		}
		if (forContext == null)
			return;
		for (ATLServiceDispatcher dispatcher : forContext.values())
			dispatcher.doForget();
	}
}
