package android.content;

import android.R;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.ContextImpl;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.Service;
import android.app.SharedPreferencesImpl;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.app.job.JobScheduler;
import android.atl.ATLLoadedApp;
import android.bluetooth.BluetoothManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ShortcutManager;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.Vibrator;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import android.view.inputmethod.InputMethodManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public abstract class Context {
	public static final String ACCOUNT_SERVICE = "account";
	private final static String TAG = "Context";

	public static final int MODE_PRIVATE = 0;
	public static final String ACTIVITY_SERVICE = "activity";
	public static final String AUDIO_SERVICE = "audio";
	public static final String CLIPBOARD_SERVICE = "clipboard";
	public static final String DISPLAY_SERVICE = "display";
	public static final String INPUT_METHOD_SERVICE = "input_method";
	public static final String LOCATION_SERVICE = "location";
	public static final String MEDIA_ROUTER_SERVICE = "media_router";
	public static final String NETWORK_STATS_SERVICE = "netstats";
	public static final String POWER_SERVICE = "power";
	public static final String VIBRATOR_SERVICE = "vibrator";
	public static final String WINDOW_SERVICE = "window";

	public static Vibrator vibrator;

	public static PackageManager package_manager;
	public static Configuration sys_config;

	// TODO: Migrate to ATLLoadedApp, cannot remove yet due to being called by native
	// The current replacement is ATLLoadedApp.getPrimaryApplication().getApplication()
	@Deprecated
	private static Application this_application;

	File data_dir = null;
	File prefs_dir = null;
	File files_dir = null;
	File obb_dir = null;
	File cache_dir = null;
	File nobackup_dir = null;

	private static final class ReceiverRegistration {
		final Context context;
		final BroadcastReceiver receiver;
		final IntentFilter filter;
		ReceiverRegistration(Context c, BroadcastReceiver r, IntentFilter f) {
			context = c; receiver = r; filter = new IntentFilter(f);
		}
	}
	private static final java.util.List<ReceiverRegistration> receiverRegistrations = new java.util.ArrayList<>();

	static {
		sys_config = new Configuration();
		native_updateConfig(sys_config);
		sys_config.applyDisplayMetrics(new android.util.DisplayMetrics());
		ATLLoadedApp primary_application = ATLLoadedApp.getPrimaryApplication();

		ApplicationInfo application_info = primary_application.pkg.applicationInfo;
		application_info.dataDir = Environment.getExternalStorageDirectory().getAbsolutePath();
		application_info.nativeLibraryDir = (new File(Environment.getExternalStorageDirectory(), "lib")).getAbsolutePath();
		package_manager = new PackageManager();

		Provider provider = new Provider("AndroidKeyStore", 1.0, "Android KeyStore provider") {};
		provider.put("KeyStore.AndroidKeyStore", "android.security.keystore.AndroidKeyStore");
		provider.put("KeyGenerator.AES", "android.security.keystore.KeyGenerator$AES");
		provider.put("KeyGenerator.HmacSHA512", "android.security.keystore.KeyGenerator$HmacSHA512");
		Security.addProvider(provider);

		// Android's AES/GCM/NoPadding cipher generates a 12-byte IV when the
		// caller supplies none; the BouncyCastle provider on the boot classpath
		// uses the AES block size (16 bytes), which breaks callers relying on
		// the Android convention (Tink/androidx.security slice a fixed 12-byte
		// IV, so the GCM tag check fails). Register an Android-compatible
		// implementation for that single service in its own provider, installed
		// with precedence; the AndroidKeyStore provider above stays last so that
		// a provider-less KeyGenerator.getInstance("AES") still resolves to BC.
		Provider cipher_provider = new Provider("ATLAndroidCrypto", 1.0, "ATL Android-compatible crypto provider") {};
		cipher_provider.put("Cipher.AES/GCM/NoPadding", "android.security.keystore.AndroidKeyStore$AesGcmCipher");
		Security.insertProviderAt(cipher_provider, 1);

		for (PackageParser.Activity receiver : primary_application.pkg.receivers) {
			if (receiver.intents == null)
				continue;
			for (PackageParser.ActivityIntentInfo intent : receiver.intents) {
				if (intent.matchAction("org.unifiedpush.android.connector.MESSAGE")) {
					nativeExportUnifiedPush(application_info.packageName);
					break;
				}
			}
		}
	}

	protected static native void native_updateConfig(Configuration config);
	protected static native void nativeOpenFile(int fd);
	protected static native void nativeShareFile(String text, int fd);
	private static native void nativeExportUnifiedPush(String packageName);
	private static native void nativeRegisterUnifiedPush(String token, String application);
	protected static native void nativeStartExternalService(Intent service);

	static Application createApplication(long native_window) throws Exception {
		Application application = ATLLoadedApp.getPrimaryApplication().getApplication();
		application.native_window = native_window;
		this_application = application;
		return application;
	}

	public Context() {
		Slog.v(TAG, "new Context! this one is: " + this);
	}

	public int checkPermission(String permission, int pid, int uid) {
		return getPackageManager().checkPermission(permission, getPackageName());
	}

	public abstract Resources.Theme getTheme();

	public abstract ApplicationInfo getApplicationInfo();

	public Context getApplicationContext() {
		return ATLLoadedApp.getPrimaryApplication().getApplication();
	}

	public ContentResolver getContentResolver() {
		return new ContentResolver();
	}

	public abstract Object getSystemService(String name);

	public abstract Object getSystemService(Class<?> serviceClass) throws InstantiationException, IllegalAccessException, InvocationTargetException;

	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
		if (receiver == null)
			return null;
		java.util.Objects.requireNonNull(filter, "filter");
		synchronized (receiverRegistrations) {
			receiverRegistrations.add(new ReceiverRegistration(this, receiver, filter));
		}
		return null; // No sticky broadcast store.
	}

	public Looper getMainLooper() {
		return Looper.getMainLooper();
	}

	public String getPackageName() {
		return getApplicationInfo().packageName;
	}

	/**
	 * @hide Return the name of the base context this context is derived from.
	 * This is the same as {@link #getOpPackageName()} except in
	 * cases where system components are loaded into other app processes, in which
	 * case {@link #getOpPackageName()} will be the name of the primary package in
	 * that process (so that app ops uid verification will work with the name).
	 * AOSP ContextImpl.getBasePackageName() (core/java/android/app/ContextImpl.java:563-567,
	 * android-17.0.0_r1, 94b4c163) falls back to getPackageName(); ATL never has a separate
	 * mBasePackageName, so the context's own package is the correct answer.
	 */
	public String getBasePackageName() {
		return getPackageName();
	}

	/**
	 * Return the package name that should be used for {@link android.app.AppOpsManager} calls from
	 * this context, so that app ops manager's uid verification will work with the name.
	 * <p>
	 * This is not generally intended for third party application developers.
	 * AOSP ContextImpl.getOpPackageName() (core/java/android/app/ContextImpl.java:570-573)
	 * returns mAttributionSource.getPackageName(); ATL has no attribution sources, so the
	 * package the context was created for is the equivalent value.
	 */
	public String getOpPackageName() {
		return getBasePackageName();
	}

	public String getPackageCodePath() {
		return getApplicationInfo().sourceDir;
	}

	public int getColor(int resId) {
		return this.getResources().getColor(resId);
	}

	public final String getString(int resId) {
		return this.getResources().getString(resId);
	}

	public final String getString(int resId, Object... formatArgs) {
		return this.getResources().getString(resId, formatArgs);
	}

	public PackageManager getPackageManager() {
		return package_manager;
	}

	public abstract Resources getResources();

	public AssetManager getAssets() {
		return getResources().getAssets();
	}

	private File makeFilename(File base, String name) {
		if (name.indexOf(File.separatorChar) < 0) {
			return new File(base, name);
		}
		throw new IllegalArgumentException(
		    "File " + name + " contains a path separator");
	}

	private File getDataDirFile() {
		if (data_dir == null) {
			data_dir = android.os.Environment.getExternalStorageDirectory();
		}
		return data_dir;
	}

	public File getDataDir() {
		return getDataDirFile();
	}

	public File getFilesDir() {
		if (files_dir == null) {
			files_dir = new File(getDataDirFile(), "files");
		}
		if (!files_dir.exists()) {
			if (!files_dir.mkdirs()) {
				if (files_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return files_dir;
				}
				Slog.w(TAG, "Unable to create files directory " + files_dir.getPath());
				return null;
			}
		}
		return files_dir;
	}

	public File getExternalFilesDir(String type) {
		return getFilesDir();
	}

	public File[] getExternalFilesDirs(String type) {
		return new File[] {getExternalFilesDir(type)};
	}

	public File getObbDir() {
		if (obb_dir == null) {
			obb_dir = new File(getDataDirFile(), "Android/obb/" + getPackageName());
		}
		if (!obb_dir.exists()) {
			if (!obb_dir.mkdirs()) {
				if (obb_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return obb_dir;
				}
				Slog.w(TAG, "Unable to create obb directory >" + obb_dir.getPath() + "<");
				return null;
			}
		}
		return obb_dir;
	}

	public File[] getObbDirs() {
		return new File[] {getObbDir()};
	}

	public File getCacheDir() {
		if (cache_dir == null) {
			// Keep cache in the selected app profile, like files and code_cache.
			// Native libraries extracted here must also be inside the linker
			// search boundary for this app; a shared /tmp cache is outside it.
			cache_dir = new File(getDataDirFile(), "cache");
		}
		if (!cache_dir.exists()) {
			if (!cache_dir.mkdirs()) {
				if (cache_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return cache_dir;
				}
				Slog.w(TAG, "Unable to create cache directory >" + cache_dir.getPath() + "<");
				return null;
			}
		}
		return cache_dir;
	}

	public File getExternalCacheDir() {
		return getCacheDir();
	}

	public File[] getExternalCacheDirs() {
		return new File[] {getCacheDir()};
	}

	public File getCodeCacheDir() {
		File dir = new File(getApplicationInfo().dataDir, "code_cache");
		if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
			throw new IllegalStateException("Cannot create code cache directory: " + dir);
		return dir;
	}

	public File getNoBackupFilesDir() {
		if (nobackup_dir == null) {
			nobackup_dir = new File(getDataDirFile(), "no_backup/" + getPackageName());
		}
		if (!nobackup_dir.exists()) {
			if (!nobackup_dir.mkdirs()) {
				if (nobackup_dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return nobackup_dir;
				}
				Slog.w(TAG, "Unable to create no_backup directory >" + nobackup_dir.getPath() + "<");
				return null;
			}
		}
		return nobackup_dir;
	}

	private File getPreferencesDir() {
		if (prefs_dir == null) {
			prefs_dir = new File(getDataDirFile(), "shared_prefs");
		}
		return prefs_dir;
	}

	public File[] getExternalMediaDirs() {
		return getExternalFilesDirs("media");
	}

	public File getDir(String name, int mode) {
		File dir = new File(getFilesDir(), name);
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				if (dir.exists()) {
					// spurious failure; probably racing with another process for this app
					return dir;
				}
				Slog.w(TAG, "Unable to create directory >" + dir.getPath() + "<");
				return null;
			}
		}
		return dir;
	}

	public File getFileStreamPath(String name) {
		return makeFilename(getFilesDir(), name);
	}

	public File getSharedPrefsFile(String name) {
		return makeFilename(getPreferencesDir(), name + ".xml");
	}

	private static Map<String, SharedPreferences> sharedPrefs = new HashMap<String, SharedPreferences>();

	public SharedPreferences getSharedPreferences(String name, int mode) {
		File file = getSharedPrefsFile(name);
		final String key;
		try { key = file.getCanonicalPath(); }
		catch (java.io.IOException e) { throw new IllegalStateException("Cannot resolve preferences path", e); }
		synchronized (sharedPrefs) {
			SharedPreferences prefs = sharedPrefs.get(key);
			if (prefs == null) {
				prefs = new SharedPreferencesImpl(file, mode);
				sharedPrefs.put(key, prefs);
			}
			return prefs;
		}
	}

	public abstract ClassLoader getClassLoader();

	public abstract ComponentName startService(Intent intent);

	/**
	 * Like {@link #startService(Intent)}, but with the implicit promise that the service
	 * calls {@link android.app.Service#startForeground} once it begins running. AOSP declares
	 * this abstract and lets {@code ContextImpl} implement it by handing a foreground flag to
	 * ActivityManager; ATL has no separate foreground-service state (every start goes through
	 * {@code ATLLoadedApp.startService}), so our implementation is the same start request.
	 */
	public abstract ComponentName startForegroundService(Intent service);

	// TODO: do these both work? make them look more alike
	public FileInputStream openFileInput(String name) throws FileNotFoundException {
		Slog.v(TAG, "openFileInput called for: '" + name + "'");
		File file = new File(getFilesDir(), name);

		return new FileInputStream(file);
	}

	public FileOutputStream openFileOutput(String name, int mode) throws java.io.FileNotFoundException {
		Slog.v(TAG, "openFileOutput called for: '" + name + "'");
		return new FileOutputStream(android.os.Environment.getExternalStorageDirectory().getPath() + "/files/" + name);
	}

	public int checkCallingOrSelfPermission(String permission) {
		return getPackageManager().checkPermission(permission, getPackageName());
	}

	public int checkSelfPermission(String permission) {
		return checkCallingOrSelfPermission(permission);
	}

	// AOSP Context.registerComponentCallbacks/unregisterComponentCallbacks
	// (android-17.0.0_r1 Context.java:1158-1168): register the callbacks with the
	// base application, which dispatches them.
	public void registerComponentCallbacks(ComponentCallbacks callbacks) {
		getApplicationContext().registerComponentCallbacks(callbacks);
	}

	public void unregisterComponentCallbacks(ComponentCallbacks callbacks) {
		getApplicationContext().unregisterComponentCallbacks(callbacks);
	}

	public abstract boolean bindService(final Intent intent, final ServiceConnection serviceConnection, int flags);

	/**
	 * Same as {@link #bindService(Intent, ServiceConnection, int)} with an executor to
	 * control the {@link ServiceConnection} callbacks. AOSP
	 * {@code Context.bindService(Intent, int, Executor, ServiceConnection)}
	 * (android-14.0.0_r1 Context.java) declares this overload
	 * non-abstract with a body that must be overridden by a subclass; the
	 * delegating override lives in {@link ContextWrapper} and the working one in
	 * {@code android.app.ContextImpl}.
	 */
	public boolean bindService(final Intent intent, int flags, final Executor executor,
	                           final ServiceConnection serviceConnection) {
		throw new RuntimeException("Not implemented. Must override in a subclass.");
	}

	/* For use from native code */
	static Activity resolveActivityInternal(Intent intent) throws ReflectiveOperationException {
		String className = null;
		ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
		if (intent.getComponent() != null) {
			className = intent.getComponent().getClassName();
		} else {
			int best_score = -5;
			for (PackageParser.Activity activity : primary.pkg.activities) {
				for (PackageParser.IntentInfo intentInfo : activity.intents) {
					int score = intentInfo.match(intent.getAction(), intent.getType(), intent.getScheme(), intent.getData(), intent.getCategories(), "Context");
					if (score > best_score && score > 0) {
						className = activity.info.targetActivity != null ? activity.info.targetActivity : activity.className;
						best_score = score;
					}
				}
			}
		}
		if (className != null) {
			return Activity.internalCreateActivity(className, primary.getNativeWindow(), intent);
		} else {
			return null;
		}
	}

	public abstract void startActivity(Intent intent);

	public void startActivity(Intent intent, Bundle options) {
		startActivity(intent);
	}

	public final TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs) {
		return getTheme().obtainStyledAttributes(set, attrs, 0, 0);
	}
	public final TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
		return getTheme().obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes);
	}
	public final TypedArray obtainStyledAttributes(int resid, int[] attrs) {
		return getTheme().obtainStyledAttributes(resid, attrs);
	}
	public final TypedArray obtainStyledAttributes(int[] attrs) {
		return getTheme().obtainStyledAttributes(attrs);
	}

	public abstract void setTheme(int resId);

	public final CharSequence getText(int resId) {
		return getResources().getText(resId);
	}

	public final ColorStateList getColorStateList(int id) {
		return getResources().getColorStateList(id);
	}

	public final Drawable getDrawable(int resId) {
		return getResources().getDrawable(resId, getTheme());
	}

	public abstract boolean isRestricted();

	public File getDatabasePath(String dbName) {
		File databaseFile;
		File databaseDir;
		if (dbName.startsWith("/")) {
			databaseFile = new File(dbName);
			databaseDir = databaseFile.getParentFile();
		} else {
			databaseDir = new File(getDataDirFile(), "databases");
			databaseFile = new File(databaseDir, dbName);
		}
		if (!databaseDir.exists())
			databaseDir.mkdirs();
		return databaseFile;
	}

	public void sendBroadcast(Intent intent) {
		if ("org.unifiedpush.android.distributor.REGISTER".equals(intent.getAction())) {
			nativeRegisterUnifiedPush(intent.getStringExtra("token"), intent.getStringExtra("application"));
		}
		String type = intent.resolveTypeIfNeeded(getContentResolver());
		java.util.List<ReceiverRegistration> snapshot;
		synchronized (receiverRegistrations) { snapshot = new java.util.ArrayList<>(receiverRegistrations); }
		java.util.Set<BroadcastReceiver> delivered = java.util.Collections.newSetFromMap(
			new java.util.IdentityHashMap<BroadcastReceiver, Boolean>());
		if (intent.getComponent() == null) for (ReceiverRegistration registration : snapshot) {
			if (intent.getPackage() != null && !intent.getPackage().equals(registration.context.getPackageName())) continue;
			if (registration.filter.match(intent.getAction(), type, intent.getScheme(), intent.getData(), intent.getCategories(), TAG) < 0) continue;
			synchronized (receiverRegistrations) { if (!receiverRegistrations.contains(registration)) continue; }
			if (delivered.add(registration.receiver)) registration.receiver.onReceive(registration.context, new Intent(intent));
		}
		ATLLoadedApp.getPrimaryApplication().receiveBroadcast(this, intent);
	}

	public void sendBroadcast(Intent intent, String receiverPermission) {
		sendBroadcast(intent);
	}

	public abstract boolean stopService(Intent intent);

	public void unbindService(ServiceConnection serviceConnection) {}

	public void unregisterReceiver(BroadcastReceiver receiver) {
		synchronized (receiverRegistrations) {
			java.util.Iterator<ReceiverRegistration> iterator = receiverRegistrations.iterator();
			while (iterator.hasNext()) if (iterator.next().receiver == receiver) iterator.remove();
		}
	}

	public static final int CONTEXT_INCLUDE_CODE = 0x00000001;
	/** Automatically create the service instance when binding to it. */
	public static final int BIND_AUTO_CREATE = 0x00000001;
	public static final int CONTEXT_IGNORE_SECURITY = 0x00000002;
	public static final int CONTEXT_RESTRICTED = 0x00000004;

	public abstract Context createPackageContext(String packageName, int flags)
	    throws PackageManager.NameNotFoundException;

	public void grantUriPermission(String dummy, Uri dummy2, int dummy3) {
		System.out.println("grantUriPermission(" + dummy + ", " + dummy2 + ", " + dummy3 + ") called");
	}

	public static final int MODE_ENABLE_WRITE_AHEAD_LOGGING = 0x0008;
	public static final int MODE_NO_LOCALIZED_COLLATORS = 0x0010;

	public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
		return openOrCreateDatabase(name, mode, factory, null);
	}

	public SQLiteDatabase openOrCreateDatabase(String filename, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
		int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
		if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
			flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
		}
		if ((mode & MODE_NO_LOCALIZED_COLLATORS) != 0) {
			flags |= SQLiteDatabase.NO_LOCALIZED_COLLATORS;
		}
		SQLiteDatabase db = SQLiteDatabase.openDatabase(filename, factory, flags, errorHandler);
		return db;
	}

	public boolean deleteDatabase(String name) {
		File dbFile = getDatabasePath(name);
		return dbFile.delete();
	}

	public abstract Context createConfigurationContext(Configuration configuration);

	public void sendOrderedBroadcast(Intent intent, String receiverPermission, BroadcastReceiver resultReceiver, Handler handler, int flags, String extra, Bundle options) {
		System.out.println("sendOrderedBroadcast(" + intent + ", " + receiverPermission + ", " + resultReceiver + ", " + handler + ", " + flags + ", " + extra + ", " + options + ") called");
	}

	public abstract Context createDisplayContext(Display display);

	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String broadcastPermission, Handler scheduler) {
		return registerReceiver(receiver, filter);
	}

	public static final int RECEIVER_VISIBLE_TO_INSTANT_APPS = 1;
	public static final int RECEIVER_EXPORTED = 2;
	public static final int RECEIVER_NOT_EXPORTED = 4;

	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
		return registerReceiver(receiver, filter, null, null, flags);
	}

	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
	                               String broadcastPermission, Handler scheduler, int flags) {
		if ((flags & RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0)
			flags |= RECEIVER_EXPORTED;
		if ((flags & (RECEIVER_EXPORTED | RECEIVER_NOT_EXPORTED)) == (RECEIVER_EXPORTED | RECEIVER_NOT_EXPORTED))
			throw new IllegalArgumentException("Receiver cannot be both exported and not exported");
		// ATL currently delivers broadcasts only within this application process.
		return registerReceiver(receiver, filter, broadcastPermission, scheduler);
	}

	public String[] fileList() {
		return new String[0];
	}

	public void revokeUriPermission(Uri uri, int mode) {
		System.out.println("revokeUriPermission(" + uri + ", " + mode + ") called");
	}

	public String getAttributionTag() {
		return null;
	}
	public abstract boolean isDeviceProtectedStorage();

	public Drawable getWallpaper() {
		return null;
	}

	public String[] databaseList() {
		File databaseDir = new File(getDataDirFile(), "databases");
		if (databaseDir.exists()) {
			return databaseDir.list();
		} else {
			return new String[0];
		}
	}

	public abstract Context createDeviceProtectedStorageContext();

	public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
		File sourceFile = sourceContext.getSharedPrefsFile(name);
		if (!sourceFile.exists()) {
			return true;
		}
		deleteSharedPreferences(name);
		return sourceFile.renameTo(getSharedPrefsFile(name));
	}

	public boolean deleteSharedPreferences(String name) {
		getSharedPrefsFile(name).delete();
		sharedPrefs.remove(name);
		return true;
	}

	public String getPackageResourcePath() {
		return get_atl_loaded_app().pkg.applicationInfo.sourceDir;
	}

	public abstract int getThemeResId();

	public abstract ATLLoadedApp get_atl_loaded_app();
}
