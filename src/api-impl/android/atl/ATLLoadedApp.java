package android.atl;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.Application;
import android.app.ContextImpl;
import android.app.Service;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Slog;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.PathClassLoader;
import dalvik.system.VMRuntime;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Hold ATL Specific application data to load apps
 */
public final class ATLLoadedApp {
	private final static String TAG = "ATLLoadedApp";
	static final HashSet<String> play_services = new HashSet<>(Arrays.asList(
	    "com.android.vending", "com.google.android.gms", "com.google.android.gsf"));
	private static ATLLoadedApp system_application;
	private static ATLLoadedApp primary_application;
	public final Resources default_resources;
	public final ClassLoader class_loader;
	public final PackageParser.Package pkg;
	public final int effective_sdk_compat;
	private final Object init_lock;
	private final ATLServiceRegistry service_registry;
	private Application application;
	private Resources.Theme default_theme;

	ATLLoadedApp(Resources resources, ClassLoader classLoader,
	             PackageParser.Package pkg) {
		this.default_resources = resources;
		this.class_loader = classLoader;
		this.pkg = pkg;
		this.effective_sdk_compat = Math.min(pkg.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT);
		this.init_lock = new Object();
		this.service_registry = new ATLServiceRegistry();
	}

	@NonNull
	public static ATLLoadedApp getSystemApplication() {
		if (system_application != null) {
			return system_application;
		}
		PackageParser packageParser = new PackageParser(null);
		Resources resources = Resources.getSystem();
		String[] outError = new String[1];
		PackageParser.Package pkg;
		try {
			pkg = packageParser.parsePackage(resources,
			                                 resources.getAssets().openXmlResourceParser(1, "AndroidManifest.xml"),
			                                 PackageParser.PARSE_IS_SYSTEM, outError);
		} catch (XmlPullParserException | IOException e) {
			throw new RuntimeException(outError[0], e);
		}
		packageParser.collectCertificates(pkg, PackageParser.PARSE_IS_SYSTEM);
		return system_application = new ATLLoadedApp(resources,
		                                             ATLLoadedApp.class.getClassLoader(), pkg);
	}

	@NonNull
	public static synchronized ATLLoadedApp getPrimaryApplication() {
		if (primary_application != null) {
			return primary_application;
		}
		final String classLoaderPath = System.getProperty("atl.app.class.path");
		final String nativePath = System.getProperty("atl.app.library.path");
		if (classLoaderPath == null && nativePath == null) {
			// Allow ATL test runner to not fail when no primary application is present
			Slog.w(TAG, "No primary application defined, using framework-res.apk as fallback");
			return primary_application = getSystemApplication();
		}
		final int pathsIndex = classLoaderPath.indexOf(':');
		try {
			return primary_application = loadFromPath(
				   pathsIndex == -1 ? classLoaderPath : classLoaderPath.substring(0, pathsIndex),
				   nativePath, classLoaderPath, true);
		} catch (IOException e) {
			// Failing to get the primary application should always be an error
			throw new Error("Failed to parse primary application", e);
		}
	}

	@NonNull
	public static ATLLoadedApp loadFromPath(String mainApk, String nativePath, String classLoaderPath) throws IOException {
		return loadFromPath(mainApk, nativePath, classLoaderPath, false);
	}

	/**
	 * @param isPrimary true only for the app named by atl.app.class.path. The
	 *                  ABI diagnostic may terminate this primary launch; loading a
	 *                  secondary package must keep normal recoverable errors.
	 */
	@NonNull
	private static ATLLoadedApp loadFromPath(String mainApk, String nativePath, String classLoaderPath, boolean isPrimary) throws IOException {
		ATLAppSplits.SplitSet splits = ATLAppSplits.resolve(classLoaderPath);
		classLoaderPath = splits.classPath;
		String dexPath = splits.dexPath;
		if (splits.baseApk != null) mainApk = splits.baseApk;
		// Before anything of this application is loaded: refuse a package whose
		// native code has no variant for the ABI this host runs, instead of
		// letting it fail later with an UnsatisfiedLinkError cascade.
		if (isPrimary)
			ATLNativeAbi.checkOrExit(classLoaderPath);
		if (ATLToggles.ATL_GHAX) {
			// Always load ghax.jar as the first element to override application library classes
			classLoaderPath = ATLPaths.ghax_jar.getPath() + File.pathSeparator + classLoaderPath;
			dexPath = ATLPaths.ghax_jar.getPath() + File.pathSeparator + dexPath;
		}
		BaseDexClassLoader classLoader = new PathClassLoader(
		    dexPath, nativePath, ATLLoadedApp.class.getClassLoader());
		AssetManager assetManager = new AssetManager(classLoaderPath + ":" + System.getProperty("java.class.path"));
		PackageParser packageParser = new PackageParser(mainApk);
		Resources resources = new Resources(assetManager, new DisplayMetrics(), Context.sys_config, classLoader);
		resources.trackWindowGeometry();
		String[] outError = new String[1];
		PackageParser.Package pkg;
		try {
			pkg = packageParser.parsePackage(resources,
			                                 assetManager.openXmlResourceParser(1, "AndroidManifest.xml"), 0, outError);
		} catch (XmlPullParserException e) {
			throw new IOException(outError[0], e);
		}
		if (packageParser.getParseError() != PackageManager.INSTALL_SUCCEEDED) {
			throw new IOException(outError[0]);
		}
		packageParser.collectCertificates(pkg, 0);
		// Support for MicroG and other custom GMS implementations.
		if (play_services.contains(pkg.packageName)) {
			ATLSigHelper.addGMSSignatures(pkg);
		}
		// ATL uses one logical Android application UID, independent of the Linux UID.
		pkg.applicationInfo.uid = android.os.Process.myUid();
		pkg.applicationInfo.splitSourceDirs = splits.splitPaths;
		pkg.applicationInfo.splitPublicSourceDirs = splits.splitPaths.clone();
		pkg.applicationInfo.sourceDir = mainApk;
		pkg.applicationInfo.publicSourceDir = mainApk;
		return new ATLLoadedApp(resources, classLoader, pkg);
	}

	public Resources createDefaultResources() {
		return this.createResources(this.default_resources.getDisplayMetrics(), Context.sys_config);
	}

	public Resources createResources(DisplayMetrics displayMetrics, Configuration configuration) {
		if (displayMetrics == null)
			displayMetrics = this.default_resources.getDisplayMetrics();
		if (configuration == null)
			configuration = Context.sys_config;
		Resources resources = new Resources(this.default_resources.getAssets(), displayMetrics, configuration, class_loader);
		if (configuration == Context.sys_config) resources.trackWindowGeometry();
		return resources;
	}

	public ContextImpl createContext(DisplayMetrics displayMetrics, Configuration configuration, int theme) {
		return new ContextImpl(this.createResources(displayMetrics, configuration), this, theme);
	}

	public ContextImpl createContext(DisplayMetrics displayMetrics, Configuration configuration, Resources.Theme theme) {
		return new ContextImpl(this.createResources(displayMetrics, configuration), this, theme);
	}

	public Resources.Theme getDefaultTheme() {
		if (this.default_theme != null)
			return this.default_theme;
		synchronized (this.init_lock) {
			if (this.default_theme != null)
				return this.default_theme;
			this.default_theme = this.default_resources.newTheme();
			this.default_theme.applyStyle(this.pkg.applicationInfo.theme, true);
		}
		return this.default_theme;
	}

	public Application getApplicationUnsafe() throws ReflectiveOperationException {
		if (this.application != null)
			return application;
		final String applicationClassName = pkg.applicationInfo.className;
		synchronized (this.init_lock) {
			if (application != null)
				return application;
			// The platform lets an application that asks for a large heap grow past
			// the default per-app cap (AOSP does this in
			// ActivityThread.handleBindApplication by calling
			// VMRuntime.getRuntime().clearGrowthLimit() for FLAG_LARGE_HEAP), so it
			// may use the maximum the launcher passed to ART as -Xmx instead of
			// stopping at -XX:HeapGrowthLimit. Must happen before the application
			// class runs, exactly as on the platform.
			if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_LARGE_HEAP) != 0) {
				VMRuntime.getRuntime().clearGrowthLimit();
			}
			if (applicationClassName != null) {
				Class<? extends Application> cls =
				    this.loadClass(pkg.applicationInfo.className)
					.asSubclass(Application.class);
				Constructor<? extends Application> constructor = cls.getConstructor();
				application = constructor.newInstance();
			} else {
				application = new Application();
			}
			application.atl_attach_base_context(new ContextImpl(
			    this.createDefaultResources(), this, pkg.applicationInfo.theme));
			// HACK: Set WhatsApp's custom logging mechanism to verbose for easier debugging.
			// Should be removed again once WhatsApp is fully supported
			try {
				this.loadClass("com.whatsapp.util.Log").getField("level").setInt(null, 5);
			} catch (Exception ignore) {
			}
		}
		return application;
	}

	public Class<?> loadClass(String name) throws ClassNotFoundException {
		return Class.forName(name, false, class_loader);
	}

	public Application getApplication() {
		try {
			return this.getApplicationUnsafe();
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public Activity createActivity(String className, Intent intent)
	    throws ReflectiveOperationException {
		int theme_res = 0;
		int label_res = 0;
		for (PackageParser.Activity activity : pkg.activities) {
			if (className.equals(activity.className)) {
				label_res = activity.info.labelRes;
				theme_res = activity.info.getThemeResource();
				break;
			}
		}

		theme_res = Resources.selectDefaultTheme(theme_res, this.effective_sdk_compat);

		Class<? extends Activity> cls = this.loadClass(className).asSubclass(Activity.class);
		Constructor<? extends Activity> constructor = cls.getConstructor();
		Activity activity = constructor.newInstance();
		intent.setComponent(new ComponentName(pkg.packageName, className));
		activity.intent = intent;
		Resources r = this.createDefaultResources();
		activity.atl_attach_base_context(new ContextImpl(r, this, theme_res));
		if (label_res != 0) {
			activity.setTitle(r.getText(label_res));
		} else if (pkg.applicationInfo.labelRes != 0) {
			activity.setTitle(r.getText(pkg.applicationInfo.labelRes));
		}
		return activity;
	}

	/**
	 * Resolves an intent to a service component DECLARED by this application.
	 * An unknown component (not declared in the manifest) resolves to null:
	 * startService must not report success for a service that cannot be found.
	 */
	public ComponentName resolveServiceComponent(Intent intent) {
		ComponentName component = intent.getComponent();
		if (component != null) {
			for (PackageParser.Service service : pkg.services) {
				if (component.getClassName().equals(service.className)) {
					requireLocalService(service);
					return component;
				}
			}
			return null;
		}
		int priority = Integer.MIN_VALUE;
		for (PackageParser.Service service : pkg.services) {
			if (service.intents == null)
				continue;
			for (PackageParser.IntentInfo intentInfo : service.intents) {
				if (intentInfo.matchAction(intent.getAction()) && intentInfo.priority > priority) {
					requireLocalService(service);
					component = new ComponentName(pkg.packageName, service.className);
					priority = intentInfo.priority;
					break;
				}
			}
		}
		return component;
	}

	private void requireLocalService(PackageParser.Service service) {
		String process = service.info.processName;
		String appProcess = pkg.applicationInfo.processName;
		if (appProcess == null) appProcess = pkg.packageName;
		if ((process != null && !process.equals(appProcess)) ||
		    (service.info.flags & android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
			throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
		}
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
		Class<? extends Service> cls = this.loadClass(record.className).asSubclass(Service.class);
		service = cls.getConstructor().newInstance();
		ContextImpl serviceContext = new ContextImpl(
		    this.createDefaultResources(), this, pkg.applicationInfo.theme);
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
		final ComponentName component = this.resolveServiceComponent(intent);
		if (component == null) {
			Slog.w(TAG, "startService: no matching service found for intent: " + intent);
			return null;
		}
		final ATLServiceRegistry.ServiceRecord record = service_registry.allocateStart(component, intent, null);
		final int startId = record.lastStartId;

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					Service service = bringUpService(record);
					// A client that bound without AUTO_CREATE is delivered its binder
					// as soon as the service comes up through startService().
					deliverBoundClients(record);
					service.onStartCommand(intent, 0, startId);
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "startService: failed to start service " + component.getClassName(), e);
				}
			}
		});

		return component;
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
		java.util.ArrayList<ATLServiceRegistry.BoundClient> clients = service_registry.bindingsOf(record);
		for (ATLServiceRegistry.BoundClient client : clients) {
			if (client.requested && !client.delivered && ensureBindingIdentity(record, client.intent))
				client.rebind = true;
		}
		for (ATLServiceRegistry.BoundClient client : clients) {
			if (!client.requested || client.delivered)
				continue;
			client.delivered = true;
			IBinder binder = service_registry.binderFor(record, client.intent);
			ATLServiceRegistry.BoundClient old = service_registry.deliveredConnection(record, client);
			if (old == null || old.deliveredBinder != binder) {
				IBinder previous = old == null ? null : old.deliveredBinder;
				service_registry.markConnectionDelivered(record, client, binder);
				if (previous != null) client.connection.onServiceDisconnected(record.component);
				if (binder == null) client.connection.onNullBinding(record.component);
				else client.connection.onServiceConnected(record.component, binder);
			}
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
		final ComponentName component = this.resolveServiceComponent(intent);
		if (component == null) {
			Slog.w(TAG, "bindService: no matching service found for intent: " + intent);
			return null;
		}
		final boolean autoCreate = (flags & Context.BIND_AUTO_CREATE) != 0;
		final ATLServiceRegistry.ServiceRecord record = service_registry.allocateBind(component, null);
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
	 * ordinary unbind.
	 */
	public void unbindService(Context context, ServiceConnection serviceConnection) {
		for (ATLServiceRegistry.UnbindResult result : service_registry.unbind(context, serviceConnection))
			postUnbindWork(result.record, result.intents, result.destroy);
	}

	/**
	 * Posts the main-looper work of a client removal: one {@code onUnbind()} per
	 * removed binding identity (deduped), then {@code onDestroy()} when the unbind
	 * left the service neither started nor bound, and finally, for a destroyed
	 * instance, the cleanup of the bindings it had issued from its own context.
	 */
	private void postUnbindWork(final ATLServiceRegistry.ServiceRecord record,
	                            final java.util.ArrayList<Intent> intents, final boolean destroy) {
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
		for (ATLServiceRegistry.UnbindResult result : service_registry.unbindContext(context)) {
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

	public void receiveBroadcast(Context context, Intent intent) {
		for (PackageParser.Activity receiver : pkg.receivers) {
			if (receiver.intents == null)
				continue;
			for (PackageParser.IntentInfo intentInfo : receiver.intents) {
				if (intentInfo.matchAction(intent.getAction())) {
					try {
						Class<? extends BroadcastReceiver> cls = this.loadClass(receiver.className).asSubclass(BroadcastReceiver.class);
						BroadcastReceiver receiverInstance = cls.newInstance();
						receiverInstance.onReceive(context, intent);
					} catch (ReflectiveOperationException e) {
						Slog.e(TAG, "startService: failed to receive broadcast " + receiver.className, e);
					}
				}
			}
		}
	}

	public long getNativeWindow() {
		return this.application == null ? 0 : this.application.native_window;
	}
}
