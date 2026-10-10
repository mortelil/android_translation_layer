package android.atl;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.Application;
import android.app.ContextImpl;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Slog;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.PathClassLoader;
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
				   nativePath, classLoaderPath);
		} catch (IOException e) {
			// Failing to get the primary application should always be an error
			throw new Error("Failed to parse primary application", e);
		}
	}

	@NonNull
	public static ATLLoadedApp loadFromPath(String mainApk, String nativePath, String classLoaderPath) throws IOException {
		if (ATLToggles.ATL_GHAX) {
			// Always load ghax.jar as the first element to override application library classes
			classLoaderPath = ATLPaths.ghax_jar.getPath() + File.pathSeparator + classLoaderPath;
		}
		BaseDexClassLoader classLoader = new PathClassLoader(
		    classLoaderPath, nativePath, ATLLoadedApp.class.getClassLoader());
		AssetManager assetManager = new AssetManager(classLoaderPath + ":" + System.getProperty("java.class.path"));
		PackageParser packageParser = new PackageParser(mainApk);
		Resources resources = new Resources(assetManager, new DisplayMetrics(), Context.sys_config, classLoader);
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
		// Loaded applications share this host process; ATL does not assign Android sandbox UIDs.
		pkg.applicationInfo.uid = android.os.Process.myUid();
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
					component = new ComponentName(pkg.packageName, service.className);
					priority = intentInfo.priority;
					break;
				}
			}
		}
		return component;
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
		if (!service_registry.isLive(record))
			return null;
		Class<? extends Service> cls = this.loadClass(record.className).asSubclass(Service.class);
		service = cls.getConstructor().newInstance();
		service.attachBaseContext(new ContextImpl(
		    this.createDefaultResources(), this, pkg.applicationInfo.theme));
		// Publish the instance before onCreate() so a re-entrant call sees it.
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
		final ATLServiceRegistry.ServiceRecord record = service_registry.allocateStart(component);
		final int startId = record.lastStartId;

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					Service service = bringUpService(record);
					if (service == null)
						return;
					service.onStartCommand(intent, 0, startId);
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "startService: failed to start service " + component.getClassName(), e);
				}
			}
		});

		return component;
	}

	/**
	 * Bind path (no binding model yet): brings the shared instance up and
	 * publishes its binder to the client on the main looper.
	 */
	public ComponentName startOrBindService(final Intent intent, final ServiceConnection serviceConnection) {
		if (serviceConnection == null) {
			return startService(intent);
		}
		final ComponentName component = this.resolveServiceComponent(intent);
		if (component == null) {
			Slog.w(TAG, "bindService: no matching service found for intent: " + intent);
			return null;
		}
		final ATLServiceRegistry.ServiceRecord record = service_registry.allocateBind(component);

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					Service service = bringUpService(record);
					if (service == null)
						return;
					serviceConnection.onServiceConnected(intent.getComponent(), service.onBind(intent));
				} catch (ReflectiveOperationException e) {
					Slog.e(TAG, "bindService: failed to bind service " + component.getClassName(), e);
				}
			}
		});

		return component;
	}

	/**
	 * stopService: detaches the record synchronously (so the returned boolean and
	 * a second stopService observe the stopped state immediately) and posts
	 * onDestroy() to the main looper, matching Android where onDestroy lands after
	 * the call has returned.
	 */
	public boolean stopService(Intent intent) {
		ComponentName component = intent.getComponent();
		if (component == null) {
			Slog.w(TAG, "stopService: no component in intent: " + intent);
			return false;
		}
		final ATLServiceRegistry.ServiceRecord record = service_registry.detach(component);
		if (record == null)
			return false;
		final Service service = record.instance;
		if (service != null) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					service.onDestroy();
				}
			});
		}
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
		if (startId >= 0 && service_registry.lastStartIdOf(record) != startId) {
			// Old start ID: must not stop newer work.
			return false;
		}
		if (!service_registry.detach(record)) {
			return false;
		}
		final Service instance = record.instance;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				instance.onDestroy();
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
