package android.atl;

import android.os.Build;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Early host-vs-APK native ABI compatibility check.
 *
 * ATL can only load native libraries for the architecture it was built for: the
 * launcher extracts {@code lib/<host ABI>/} out of the application package
 * (main.c: extract_from_apk("lib/" NATIVE_ARCH "/", "lib/"), with NATIVE_ARCH
 * published to the VM as {@code atl.native.arch}). An APK that ships native
 * code only for other ABIs therefore cannot work, but without this check the
 * failure first surfaces deep inside application code as an
 * {@link UnsatisfiedLinkError} from System.loadLibrary ("couldn't find
 * \"libfoo.so\"") and can cascade into a JNI abort (a JNI call made while that
 * exception is pending, e.g. NewStringUTF -> SIGABRT).
 *
 * This mirrors AOSP's INSTALL_FAILED_CPU_ABI_INCOMPATIBLE rule: a package with
 * native code but no variant for the device's CPU cannot be used. It is run
 * before any application class is loaded and before the package is parsed, so
 * it also covers inputs (containers such as .apkm bundles) whose native code
 * lives in nested APK members and which would otherwise fail even earlier.
 *
 * On an incompatible package it reports the host ABI, the ABIs the package
 * declares and the native libraries that are unavailable, sets the ABI_BLOCKED
 * application status and terminates with a well-defined exit code. No JNI call
 * is made with an exception pending: the check is reached with none in flight
 * and unwinds through System.exit().
 */
public final class ATLNativeAbi {
	public static final String TAG = "ATLNativeAbi";

	/** Application status reported when the package cannot run on this host. */
	public static final String STATUS_ABI_BLOCKED = "ABI_BLOCKED";

	/** Process exit code for the ABI_BLOCKED status (0/1/10 are used elsewhere). */
	public static final int EXIT_ABI_BLOCKED = 12;

	/**
	 * Upper bound on the number of native library names printed per diagnostic.
	 * The list is what identifies the failure to the user, so it is deliberately
	 * large enough for the packages seen in practice (31 libraries for the
	 * YouTube app) instead of a short prefix.
	 */
	private static final int MAX_LISTED_LIBS = 64;

	private ATLNativeAbi() {}

	/**
	 * Check the APK set of one application against this host and terminate when
	 * it cannot run here.
	 *
	 * @param apkClassPath colon-separated APK paths of the application: the
	 *                     value of {@code atl.app.class.path} (base APK plus any
	 *                     splits) for the primary app. Do not use this terminating
	 *                     diagnostic for secondary package loads.
	 */
	public static void checkOrExit(String apkClassPath) {
		if (apkClassPath == null || apkClassPath.isEmpty())
			return;

		final String hostAbi = hostAbi();
		if (hostAbi == null)
			return; // Unknown host architecture: cannot judge, never block.

		List<String> apks = new ArrayList<>();
		for (String path : apkClassPath.split(":")) {
			if (!path.isEmpty() && !apks.contains(path))
				apks.add(path);
		}

		TreeSet<String> declaredAbis = new TreeSet<>();
		TreeSet<String> declaredLibs = new TreeSet<>();
		for (String path : apks) {
			try {
				scanPackage(new File(path), declaredAbis, declaredLibs);
			} catch (IOException e) {
				// A package that cannot be read is not this check's failure to
				// report: the loader will report it with full context.
				Slog.w(TAG, "ABI check: cannot inspect " + path + ": " + e);
			}
		}

		if (declaredAbis.isEmpty())
			return; // Pure Java package: no native code to be incompatible.
		if (declaredAbis.contains(hostAbi))
			return; // A native library for this host exists.

		reportAndExit(hostAbi, apks, declaredAbis, declaredLibs);
	}

	/**
	 * The ABI ATL loads native code for. The launcher passes the compile-time
	 * NATIVE_ARCH, which is exactly the {@code lib/<abi>/} directory it extracts;
	 * Build.CPU_ABI is the fallback for callers without the property.
	 */
	private static String hostAbi() {
		String abi = System.getProperty("atl.native.arch");
		if (abi != null && !abi.isEmpty())
			return abi;
		abi = Build.CPU_ABI;
		if (abi != null && !abi.isEmpty())
			return abi;
		return null;
	}

	/**
	 * Collect the ABIs and native library names declared by one package, looking
	 * inside nested {@code *.apk} members when the package itself declares none
	 * (an .apkm bundle keeps its per-ABI splits there).
	 */
	private static void scanPackage(File apk, TreeSet<String> abis, TreeSet<String> libs) throws IOException {
		try (ZipFile zip = new ZipFile(apk)) {
			List<ZipEntry> nested = new ArrayList<>();
			boolean found = false;
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (recordNativeEntry(name, abis, libs))
					found = true;
				else if (isNestedPackage(name))
					nested.add(entry);
			}
			if (found || zip.getEntry("AndroidManifest.xml") != null)
				return;
			// Every nested member is scanned: a later split may add the host ABI
			// that makes the whole set compatible.
			for (ZipEntry entry : nested) {
				InputStream in = null;
				try {
					in = zip.getInputStream(entry);
					scanNestedPackage(in, abis, libs);
				} catch (IOException e) {
					Slog.w(TAG, "ABI check: cannot inspect " + entry.getName() + " inside " + apk + ": " + e);
				} finally {
					if (in != null) {
						try {
							in.close();
						} catch (IOException ignored) {
						}
					}
				}
			}
		}
	}

	private static void scanNestedPackage(InputStream in, TreeSet<String> abis, TreeSet<String> libs)
	    throws IOException {
		ZipInputStream zip = new ZipInputStream(in);
		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			// getNextEntry skips the remainder of the previous member.
			recordNativeEntry(entry.getName(), abis, libs);
		}
	}

	/**
	 * Record a {@code lib/<abi>/<name>.so} member. Directory entries and
	 * anything nested deeper are ignored.
	 */
	private static boolean recordNativeEntry(String name, TreeSet<String> abis, TreeSet<String> libs) {
		if (!name.startsWith("lib/"))
			return false;
		int abiEnd = name.indexOf('/', 4);
		if (abiEnd <= 4)
			return false;
		String file = name.substring(abiEnd + 1);
		if (file.isEmpty() || file.indexOf('/') >= 0 || !file.endsWith(".so"))
			return false;
		abis.add(name.substring(4, abiEnd));
		libs.add(file);
		return true;
	}

	private static boolean isNestedPackage(String name) {
		return name.endsWith(".apk") && !name.startsWith("META-INF/");
	}

	private static void reportAndExit(String hostAbi, List<String> apks, TreeSet<String> abis,
	                                  TreeSet<String> libs) {
		StringBuilder sb = new StringBuilder();
		sb.append("ATL APP STATUS=").append(STATUS_ABI_BLOCKED).append('\n');
		sb.append("ATL ").append(STATUS_ABI_BLOCKED).append(": host ABI is ").append(hostAbi)
		    .append(" (ATL loads native code from lib/").append(hostAbi).append("/ only");
		String systemAbis = join(Build.SUPPORTED_ABIS);
		if (!systemAbis.isEmpty())
			sb.append("; system supported ABIs: ").append(systemAbis);
		sb.append(")\n");
		sb.append("ATL ").append(STATUS_ABI_BLOCKED).append(": package declares native ABIs [")
		    .append(join(abis)).append("], none matching the host ABI\n");
		for (String apk : apks)
			sb.append("ATL ").append(STATUS_ABI_BLOCKED).append(": inspected ").append(apk).append('\n');
		sb.append("ATL ").append(STATUS_ABI_BLOCKED).append(": native libraries unavailable for ")
		    .append(hostAbi).append(" (").append(libs.size()).append(" total): ")
		    .append(listedLibs(libs)).append('\n');
		sb.append("ATL APP STATUS=").append(STATUS_ABI_BLOCKED)
		    .append(" host_abi=").append(hostAbi)
		    .append(" apk_abis=").append(join(abis))
		    .append(" unavailable_libs=").append(libs.size()).append('\n');

		String diagnostic = sb.toString();
		// Both streams: ui-smoke captures them separately, probe runs read either.
		System.err.print(diagnostic);
		System.err.flush();
		System.out.print(diagnostic);
		System.out.flush();
		System.exit(EXIT_ABI_BLOCKED);
	}

	private static String listedLibs(TreeSet<String> libs) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (String lib : libs) {
			if (i == MAX_LISTED_LIBS) {
				sb.append(", and ").append(libs.size() - MAX_LISTED_LIBS).append(" more");
				break;
			}
			if (i > 0)
				sb.append(", ");
			sb.append(lib);
			i++;
		}
		return sb.toString();
	}

	private static String join(String[] values) {
		StringBuilder sb = new StringBuilder();
		if (values != null) {
			for (String value : values) {
				if (value == null || value.isEmpty())
					continue;
				if (sb.length() > 0)
					sb.append(", ");
				sb.append(value);
			}
		}
		return sb.toString();
	}

	private static String join(Iterable<String> values) {
		StringBuilder sb = new StringBuilder();
		for (String value : values) {
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(value);
		}
		return sb.toString();
	}
}
