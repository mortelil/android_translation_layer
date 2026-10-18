package android.atl;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageParser;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Slog;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public final class ATLLoadedAppManager {
	private static final String TAG = "ATLLoadedAppManager";
	private static final HashMap<String, ATLLoadedApp> loaded_apps = new HashMap<>();
	private static final HashMap<String, PackageParser.Package> loaded_packages = new HashMap<>();
	private static final Object load_lock = new Object();
	/** Apps resolved for {@link Context#createPackageContext} without their code. */
	private static final HashMap<String, ATLLoadedApp> package_only_apps = new HashMap<>();
	private static final HashSet<String> always_visible_packages = new HashSet<>();
	private static final HashSet<String> always_allow_query_all_packages = new HashSet<>();

	static {
		// Always allow MicroG to be visible for application compatibility.
		always_visible_packages.addAll(ATLLoadedApp.play_services);
		// Always allow MicroG to see all applications for compatibility.
		always_allow_query_all_packages.addAll(ATLLoadedApp.play_services);
	}

	private ATLLoadedAppManager() {}

	@Nullable
	public static PackageParser.Package getPackageFromPackageName(String packageName) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app.pkg.packageName.equals(packageName)) {
			return app.pkg;
		}
		if ("android".equals(packageName) || "atl".equals(packageName)) {
			return ATLLoadedApp.getSystemApplication().pkg;
		}
		if (!isPackageVisible(app.pkg.packageName, packageName)) return null;
		app = loaded_apps.get(packageName);
		if (app != null) {
			return app.pkg;
		}
		PackageParser.Package pkg = loaded_packages.get(packageName);
		if (pkg != null) {
			return pkg;
		}
		return loadPackage(packageName);
	}

	@Nullable
	public static ATLLoadedApp peekAppFromPackageName(String packageName) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app.pkg.packageName.equals(packageName)) {
			return app;
		}
		if ("android".equals(packageName) || "atl".equals(packageName)) {
			return ATLLoadedApp.getSystemApplication();
		}
		return loaded_apps.get(packageName);
	}

	@Nullable
	public static ATLLoadedApp getAppFromPackageName(String packageName) {
		ATLLoadedApp app = peekAppFromPackageName(packageName);
		if (app != null) {
			return app;
		}
		return loadApplication(packageName);
	}

	/**
	 * Resolves a package that the primary application is allowed to see into an ATLLoadedApp
	 * that carries the package's manifest data and its own Resources/AssetManager, without
	 * loading that package's dex or native code: resolving it only parses its manifest
	 * ({@link #loadPackage}). ATL cannot attach a class loader for one other APK only, so the
	 * code of another package comes from {@link #getAppFromPackageName} — reached by
	 * ContextImpl.createPackageContext when the caller sets {@link Context#CONTEXT_INCLUDE_CODE},
	 * and already used by ATL's own PackageManager resource accessors. A package that ATL has
	 * loaded that way is returned as it is, one object per package.
	 *
	 * @return null when the package is not installed, or not visible to the primary
	 *         application. ContextImpl.createPackageContext turns that into a
	 *         PackageManager.NameNotFoundException, like AOSP does.
	 */
	@Nullable
	public static ATLLoadedApp getPackageOnlyAppFromPackageName(String packageName) {
		ATLLoadedApp app = peekAppFromPackageName(packageName);
		if (app != null) {
			return app;
		}
		synchronized (load_lock) {
			app = package_only_apps.get(packageName);
			if (app != null) {
				return app;
			}
			// getPackageFromPackageName enforces package visibility and returns the manifest
			// data of the installed package (or of the play-services stub) without loading code.
			PackageParser.Package pkg = getPackageFromPackageName(packageName);
			if (pkg == null) {
				return null;
			}
			// A package with an APK of its own gets its own resources; the synthetic
			// play-services stub has none and shares the system ones.
			File installed_apk = new File(ATLPaths.installed_apks_dir, packageName + ".apk");
			Resources resources = installed_apk.isFile()
			    ? new Resources(new AssetManager(installed_apk.getPath()), new DisplayMetrics(),
			                    Context.sys_config, ClassLoader.getSystemClassLoader())
			    : Resources.getSystem();
			app = new ATLLoadedApp(resources, ClassLoader.getSystemClassLoader(), pkg);
			package_only_apps.put(packageName, app);
			return app;
		}
	}

	private static PackageParser.Package loadPackage(String packageName) {
		if (!isPackageVisible(ATLLoadedApp.getPrimaryApplication().pkg.packageName, packageName)) {
			// No right to view this package.
			return null;
		}
		PackageParser.Package pkg;
		synchronized (load_lock) {
			ATLLoadedApp app = loaded_apps.get(packageName);
			if (app != null) {
				return app.pkg;
			}
			pkg = loaded_packages.get(packageName);
			if (pkg != null) {
				return pkg;
			}
			File installed_apk_location = new File(ATLPaths.installed_apks_dir, packageName + ".apk");
			if (!installed_apk_location.isFile()) {
				if (ATLLoadedApp.play_services.contains(packageName)) {
					return loadGStub(packageName).pkg;
				}
				return null;
			}
			PackageParser packageParser = new PackageParser(installed_apk_location.getPath());
			pkg = packageParser.parsePackage(installed_apk_location,
			                                 installed_apk_location.getPath(), new DisplayMetrics(), 0);
			if (pkg != null) {
				packageParser.collectCertificates(pkg, 0);
				if (ATLLoadedApp.play_services.contains(packageName)) {
					ATLSigHelper.addGMSSignatures(pkg);
				}
				loaded_packages.put(packageName, pkg);
			} else {
				Slog.e(TAG, "Failed to load package " + packageName);
			}
		}
		return pkg;
	}

	private static ATLLoadedApp loadApplication(String packageName) {
		if (!isPackageVisible(ATLLoadedApp.getPrimaryApplication().pkg.packageName, packageName)) {
			// No right to view this package.
			return null;
		}
		ATLLoadedApp app;
		synchronized (load_lock) {
			app = loaded_apps.get(packageName);
			if (app != null) {
				return app;
			}
			Slog.i(TAG, "Attempting to load " + packageName + " into " + ATLLoadedApp.getPrimaryApplication().pkg.packageName);
			File installed_apk_location = new File(ATLPaths.installed_apks_dir, packageName + ".apk");
			if (!installed_apk_location.isFile()) {
				if (ATLLoadedApp.play_services.contains(packageName)) {
					return loadGStub(packageName);
				}
				return null;
			}
			String apk_path = installed_apk_location.getPath();
			String lib_path = ensureNativeLibraryDir(installed_apk_location);
			try {
				app = ATLLoadedApp.loadFromPath(apk_path, lib_path, apk_path);
				app.pkg.applicationInfo.nativeLibraryDir = lib_path;
				app.pkg.applicationInfo.sourceDir = apk_path;
				Slog.i(TAG, "loadApplication(\"" + packageName + "\")");
				loaded_packages.put(packageName, app.pkg);
				loaded_apps.put(packageName, app);
			} catch (IOException e) {
				Slog.e(TAG, "Failed to load " + packageName, e);
				return null;
			}
		}
		return app;
	}

	private static boolean isPackageVisible(String sourcePackageName, String targetPackageName) {
		if (always_visible_packages.contains(targetPackageName) || always_allow_query_all_packages.contains(sourcePackageName)) return true;
		PackageParser.Package source = ATLLoadedApp.getPrimaryApplication().pkg;
		return source.packageName.equals(sourcePackageName) && (
		    source.packageName.equals(targetPackageName) ||
		    source.applicationInfo.targetSdkVersion < 30 ||
		    source.requestedPermissions.contains("android.permission.QUERY_ALL_PACKAGES") ||
		    source.queriesPackages.contains(targetPackageName));
	}

	/**
	 * Install-time native-library extraction for a package that is only loaded
	 * at runtime.
	 *
	 * <p>Android extracts {@code lib/<abi>/} of an installed apk into the
	 * directory published as {@link android.content.pm.ApplicationInfo#nativeLibraryDir}
	 * ({@code NativeLibraryHelper.createNativeLibrarySubdir}, called from
	 * PackageManagerService install; frameworks-base pinned 94b4c163b7df). The
	 * launcher mirrors that for the package it starts
	 * ({@code extract_native_libraries}, main.c:435). A companion apk resolved
	 * later out of {@code _installed_apks_} has no such directory, so
	 * {@code nativeLibraryDir} would be null - a state no installed app sees.
	 * microG's {@code DynamiteContextFactory.createClassLoader} dereferences it to
	 * build the PathClassLoader library path and dies in
	 * {@code StringBuilder.<init>} on the null, which keeps its conscrypt
	 * provider out of {@code ProviderInstaller}.
	 *
	 * @param apk the installed companion apk
	 * @return the directory to publish as nativeLibraryDir (never null)
	 */
	private static String ensureNativeLibraryDir(File apk) {
		File lib_dir = new File(new File(ATLPaths.app_data_dir_base, apk.getName() + "_"), "lib");
		if (!lib_dir.isDirectory() && !lib_dir.mkdirs() && !lib_dir.isDirectory()) {
			Slog.e(TAG, "Failed to create native library directory " + lib_dir);
			return lib_dir.getPath();
		}
		String abi = ATLNativeAbi.hostAbi();
		if (abi == null)
			return lib_dir.getPath();
		String prefix = "lib/" + abi + "/";
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
			java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				java.util.zip.ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || !name.startsWith(prefix))
					continue;
				String file_name = name.substring(prefix.length());
				if (file_name.isEmpty() || file_name.indexOf('/') != -1)
					continue;
				File target = new File(lib_dir, file_name);
				if (target.isFile() && target.length() == entry.getSize()
				    && target.lastModified() >= apk.lastModified())
					continue;
				try (java.io.InputStream in = zip.getInputStream(entry);
				     java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
					byte[] buffer = new byte[64 * 1024];
					int read;
					while ((read = in.read(buffer)) > 0)
						out.write(buffer, 0, read);
				}
				// NativeLibraryHelper installs the directory and its contents 0755.
				target.setReadable(true, false);
				target.setExecutable(true, false);
			}
		} catch (IOException e) {
			Slog.e(TAG, "Failed to extract native libraries from " + apk, e);
		}
		return lib_dir.getPath();
	}

	private static ATLLoadedApp loadGStub(String packageName) {
		ATLLoadedApp app = ATLGStub.loadFakePlayServices(packageName);
		Slog.i(TAG, "loadGStub(\"" + packageName + "\")");
		loaded_packages.put(packageName, app.pkg);
		loaded_apps.put(packageName, app);
		return app;
	}
}
