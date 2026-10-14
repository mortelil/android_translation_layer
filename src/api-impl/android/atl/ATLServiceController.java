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
 * runs in the loader's own process (null) or in a process of its own. Placing a
 * service in another process is not wired to a process backend yet, so
 * {@link #placementRemoteProcess} still refuses it exactly as before.
 */
public final class ATLServiceController {
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

	public ATLServiceController(ATLLoadedApp app) {
		this.app = app;
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
		ComponentName component = intent.getComponent();
		if (component != null) {
			for (PackageParser.Service service : app.pkg.services) {
				if (component.getClassName().equals(service.className)) {
					return new Resolved(component, resolvePlacement(service));
				}
			}
			return null;
		}
		String remoteProcess = null;
		int priority = Integer.MIN_VALUE;
		for (PackageParser.Service service : app.pkg.services) {
			if (service.intents == null)
				continue;
			for (PackageParser.IntentInfo intentInfo : service.intents) {
				if (intentInfo.matchAction(intent.getAction()) && intentInfo.priority > priority) {
					remoteProcess = resolvePlacement(service);
					component = new ComponentName(app.pkg.packageName, service.className);
					priority = intentInfo.priority;
					break;
				}
			}
		}
		return component == null ? null : new Resolved(component, remoteProcess);
	}

	/**
	 * Where {@code service} runs: null for the loader's own process, otherwise the
	 * manifest-declared process. An isolated service is refused, and so is a
	 * service in another process until {@link #placementRemoteProcess} accepts it.
	 * The refusals keep the message the local-service probe's unsupported-remote
	 * check (SVC-014) expects.
	 */
	private String resolvePlacement(PackageParser.Service service) {
		if ((service.info.flags & android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
			throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
		}
		String process = service.info.processName;
		String appProcess = app.pkg.applicationInfo.processName;
		if (appProcess == null) appProcess = app.pkg.packageName;
		if (process == null || process.equals(appProcess)) {
			return null;
		}
		return placementRemoteProcess(service, process);
	}

	/**
	 * SEAM for the process backend wiring: the single place that decides whether a
	 * service placed in {@code process} (not the loader's own) may run there. It
	 * returns the process name to place the record in, or throws when it may not.
	 * No backend is wired yet, so it always refuses; the callers have no remote
	 * branch, so nothing may return a name from here before they get one.
	 */
	private String placementRemoteProcess(PackageParser.Service service, String process) {
		throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
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
		final ATLServiceRegistry.ServiceRecord record =
		    service_registry.allocateBind(component, resolved.remoteProcess);
		// The dispatcher exists before the client is registered, so no delivery can
		// find a registered client without one.
		getServiceDispatcher(bindContext, serviceConnection);
		final ATLServiceRegistry.BoundClient client =
		    service_registry.registerBinding(record, serviceConnection, intent, bindContext, autoCreate);

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				// The bind op has now been processed on the main looper: mark it so
				// deliverBoundClients() sees it. A bind without BIND_AUTO_CREATE stays
				// pending (requested but not delivered) until a later op brings the
				// instance up.
				client.requested = true;
				try {
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
		for (ATLServiceRegistry.UnbindResult result : results)
			postUnbindWork(result.record, result.intents, result.destroy);
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
		ArrayList<ATLServiceRegistry.UnbindResult> results = service_registry.unbindContext(context);
		forgetContextDispatchers(context);
		for (ATLServiceRegistry.UnbindResult result : results) {
			postUnbindWork(result.record, result.intents, result.destroy);
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
	 */
	public boolean stopServiceFromSelf(Service service, int startId) {
		final ATLServiceRegistry.ServiceRecord record = service_registry.findByInstance(service);
		if (record == null) {
			// Not started / never started, or already stopped.
			return false;
		}
		ATLServiceRegistry.StopResult result = service_registry.stopSelf(record, startId);
		if (!result.running) return false;
		if (result.keptBound) return true;
		final Service instance = record.instance;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				instance.onDestroy();
				unbindInstanceContextClients(record);
			}
		});
		return true;
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
