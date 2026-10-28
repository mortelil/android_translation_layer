package android.os;

import java.util.Properties;

public class SystemProperties {
	private static final Properties properties = new Properties();
	// Property reads are often on rendering/media hot paths. Keep verbose
	// diagnostics opt-in, including their string construction.
	private static final boolean DEBUG_READS = "1".equals(System.getenv("ATL_DEBUG_SYSTEM_PROPERTIES"));

	static {
		properties.put("ro.product.brand", "google");
		properties.put("ro.build.tags", "release-keys");
		properties.put("ro.build.type", "user");
		// AOSP release builds (frameworks/base Build.VERSION.CODENAME) report
		// "REL" and have no active development codenames, so
		// RESOURCES_SDK_INT == SDK_INT. ATL only ever presents release levels.
		properties.put("ro.build.version.codename", "REL");
		// TODO how to actually get the system's supported abis?
		if (System.getProperty("os.arch").equals("x86_64")) {
			properties.put("ro.product.cpu.abi", "x86_64");
			properties.put("ro.product.cpu.abi2", "x86");
			properties.put("ro.product.cpu.abilist", "x86_64,x86");
			properties.put("ro.product.cpu.abilist64", "x86_64");
			properties.put("ro.product.cpu.abilist32", "x86");
		} else if (System.getProperty("os.arch").equals("aarch64")) {
			properties.put("ro.product.cpu.abi", "arm64-v8a");
			properties.put("ro.product.cpu.abilist", "arm64-v8a");
			properties.put("ro.product.cpu.abilist64", "arm64-v8a");
		}
	}

	private static String value(String prop) {
		return "ro.build.version.sdk".equals(prop) ? String.valueOf(Build.VERSION.SDK_INT) : properties.getProperty(prop);
	}

	public static String get(String prop) {
		if (DEBUG_READS) android.util.Log.i("SystemProperties", "Grabbing String prop " + prop);
		return value(prop);
	}

	public static String get(String prop, String def) {
		if (DEBUG_READS) android.util.Log.i("SystemProperties", "Grabbing String prop " + prop + ", default " + def);
		String val = value(prop);
		return val == null ? def : val;
	}

	public boolean getBoolean(String prop, boolean def) {
		if (DEBUG_READS) android.util.Log.i("SystemProperties", "Grabbing boolean prop " + prop + ", default " + def);
		String val = value(prop);
		return val == null ? def : Boolean.parseBoolean(val);
	}

	public static int getInt(String prop, int def) {
		if (DEBUG_READS) android.util.Log.i("SystemProperties", "Grabbing int prop " + prop + ", default " + def);
		String val = value(prop);
		return val == null ? def : Integer.parseInt(val);
	}

	public static long getLong(String prop, long def) {
		if (DEBUG_READS) android.util.Log.i("SystemProperties", "Grabbing long prop " + prop + ", default " + def);
		String val = value(prop);
		return val == null ? def : Long.parseLong(val);
	}
}
