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
	private final ATLServiceController service_controller;
	private Application application;
	private Resources.Theme default_theme;

	ATLLoadedApp(Resources resources, ClassLoader classLoader,
	             PackageParser.Package pkg) {
		this.default_resources = resources;
		this.class_loader = classLoader;
		this.pkg = pkg;
		this.effective_sdk_compat = Math.min(pkg.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT);
		this.init_lock = new Object();
		this.service_controller = new ATLServiceController(this);
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
	 * Resolves an intent to a service component DECLARED by this application;
	 * see {@link ATLServiceController#resolveServiceComponent}.
	 */
	public ComponentName resolveServiceComponent(Intent intent) {
		return service_controller.resolveServiceComponent(intent);
	}

	/** startService; the service policy lives in {@link ATLServiceController#startService}. */
	public ComponentName startService(final Intent intent) {
		return service_controller.startService(intent);
	}

	/** bindService; see {@link ATLServiceController#bindService}. */
	public ComponentName bindService(final Context bindContext, final Intent intent,
	                                 final ServiceConnection serviceConnection, final int flags) {
		return service_controller.bindService(bindContext, intent, serviceConnection, flags);
	}

	/** unbindService; see {@link ATLServiceController#unbindService}. */
	public void unbindService(Context context, ServiceConnection serviceConnection) {
		service_controller.unbindService(context, serviceConnection);
	}

	/** stopService; see {@link ATLServiceController#stopService}. */
	public boolean stopService(Intent intent) {
		return service_controller.stopService(intent);
	}

	/**
	 * stopSelf()/stopSelf(int)/stopSelfResult(int) entry point for a live
	 * instance; see {@link ATLServiceController#stopServiceFromSelf}.
	 */
	public boolean stopServiceFromSelf(Service service, int startId) {
		return service_controller.stopServiceFromSelf(service, startId);
	}

	public void receiveBroadcast(Context context, Intent intent) {
		if (pkg.receivers == null || !pkg.applicationInfo.enabled) return;
		ComponentName component = intent.getComponent();
		String target = component == null ? intent.getPackage() : component.getPackageName();
		if (target != null && !target.equals(pkg.packageName)) return;
		String type = intent.resolveTypeIfNeeded(context.getContentResolver());
		for (PackageParser.Activity receiver : pkg.receivers) {
			if (receiver == null || !receiver.info.enabled) continue;
			boolean matches = component != null && component.equals(receiver.getComponentName());
			if (component == null && receiver.intents != null) for (PackageParser.IntentInfo filter : receiver.intents) {
				if (filter.match(intent.getAction(), type, intent.getScheme(), intent.getData(), intent.getCategories(), TAG) >= 0) { matches = true; break; }
			}
			if (!matches) continue;
			try {
				Class<? extends BroadcastReceiver> cls = loadClass(receiver.className).asSubclass(BroadcastReceiver.class);
				cls.newInstance().onReceive(getApplication(), new Intent(intent));
			} catch (ReflectiveOperationException e) {
				Slog.e(TAG, "Failed to receive broadcast " + receiver.className, e);
			}
		}
	}

	public long getNativeWindow() {
		return this.application == null ? 0 : this.application.native_window;
	}
}
