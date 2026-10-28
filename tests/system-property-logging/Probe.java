import android.os.SystemProperties;
import android.util.Log;
public class Probe {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        boolean debug = args[0].equals("debug");
        SystemProperties properties = new SystemProperties();
        check("google".equals(SystemProperties.get("ro.product.brand")));
        check("fallback".equals(SystemProperties.get("missing", "fallback")));
        check(properties.getBoolean("missing", true));
        check(SystemProperties.getInt("missing", 7) == 7);
        check(SystemProperties.getLong("missing", 9L) == 9L);
        check("33".equals(SystemProperties.get("ro.build.version.sdk")));
        for (int i=0; i<10000; i++) check("false".equals(SystemProperties.get("tiktok_systrace", "false")));
        check(Log.calls == (debug ? 10006 : 0));
        System.out.println("PROPERTY_LOGGING_PASS " + args[0]);
    }
}
