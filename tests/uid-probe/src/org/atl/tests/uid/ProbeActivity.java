/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.uid;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * J-UID probe: Android logical UID semantics.
 *
 * For every value in the frozen controlled value set it records the results of
 *   Process.isApplicationUid(int)          (public SDK method: direct call)
 *   UserHandle.isApp(int)                  (hidden: reflection)
 *   UserHandle.isIsolated(int)             (hidden: reflection)
 * and the process's own identity (Process.myUid() and its decomposition).
 *
 * Reachability strategy (see CONTRACT.md): the probe compiles against the public
 * android-37.0 SDK stubs. Public {@code Process} methods are called directly;
 * hidden/&#64;UnsupportedAppUsage surfaces (UserHandle.isApp/isIsolated/isCore/
 * getAppId/getUserId, Process.isIsolated(int), the isolated-UID constants and
 * PER_USER_RANGE) are reached by reflection on targetSdk 24. A surface that is
 * genuinely unreachable is recorded as "unreachable: &lt;reason&gt;" with a null
 * value -- never a fabricated boolean.
 *
 * Emits a single sentinel-wrapped JSON object (JUID_JSON_BEGIN/_END) on stdout,
 * persists the same JSON to files/results.json, then prints the completion
 * marker JUID_DONE. Completion is the marker / results.json, never process exit
 * (runbook section 8).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "JUID";
    private static final String TASK_ID = "J-UID";
    private static final String SENTINEL_BEGIN = "JUID_JSON_BEGIN";
    private static final String SENTINEL_END = "JUID_JSON_END";
    private static final String DONE_MARKER = "JUID_DONE";

    /** Frozen controlled value set (must match verify_uid_semantics.py). */
    private static final int[] VALUES = {
            0, 1000, 1001, 2000, 9999, 10000, 19999, 20000,
            90000, 98999, 99000, 99999, 100000, -1, Integer.MAX_VALUE
    };

    private final String runId =
            "j-uid-apk-" + Long.toHexString(System.currentTimeMillis()) + "-"
                    + Integer.toHexString((int) (Math.random() * 0xffff));

    /** Reachability status per hidden surface. */
    private final Map<String, String> reach = new LinkedHashMap<String, String>();
    private final List<String> exceptions = new ArrayList<String>();

    private Method mProcIsIsolatedInt;
    private Method mUhIsApp;
    private Method mUhIsIsolated;
    private Method mUhIsCore;
    private Method mUhGetAppId;
    private Method mUhGetUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json;
        try {
            json = runProbe();
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            json = "{\"task_id\":" + jsonString(TASK_ID)
                    + ",\"run_id\":" + jsonString(runId)
                    + ",\"status\":\"ERROR\",\"error\":"
                    + jsonString(t.getClass().getName() + ": " + t.getMessage())
                    + "}";
        }
        System.out.println(SENTINEL_BEGIN);
        System.out.println(json);
        System.out.println(SENTINEL_END);
        writeResults(json);
        System.out.println(DONE_MARKER);
    }

    private String runProbe() throws Exception {
        resolveReflectiveSurfaces();

        String apkSha = sha256Hex(new File(getPackageCodePath()));
        int targetSdk = getApplicationInfo().targetSdkVersion;
        int minSdk = getApplicationInfo().minSdkVersion;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"thread\":").append(jsonString(Thread.currentThread().getName()));
        sb.append(",\"status\":\"").append(exceptions.isEmpty() ? "OK" : "ERROR");
        sb.append("\",\"exceptions\":[");
        for (int i = 0; i < exceptions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(exceptions.get(i)));
        }
        sb.append(']');

        sb.append(",\"config\":{\"valueSet\":[");
        for (int i = 0; i < VALUES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(VALUES[i]);
        }
        sb.append("],\"targetSdk\":").append(targetSdk);
        sb.append(",\"minSdk\":").append(minSdk);
        sb.append('}');

        sb.append(",\"completion\":{\"marker\":").append(jsonString(DONE_MARKER));
        sb.append(",\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END)).append('}');

        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');

        sb.append(",\"reachability\":{");
        boolean firstReach = true;
        for (Map.Entry<String, String> e : reach.entrySet()) {
            if (!firstReach) {
                sb.append(',');
            }
            firstReach = false;
            sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
        }
        sb.append('}');

        sb.append(",\"constants\":{");
        sb.append("\"firstApplicationUid\":").append(Process.FIRST_APPLICATION_UID);
        sb.append(",\"lastApplicationUid\":").append(Process.LAST_APPLICATION_UID);
        appendIntField(sb, "firstIsolatedUid", "android.os.Process",
                "FIRST_ISOLATED_UID", "Process.FIRST_ISOLATED_UID");
        appendIntField(sb, "lastIsolatedUid", "android.os.Process",
                "LAST_ISOLATED_UID", "Process.LAST_ISOLATED_UID");
        appendIntField(sb, "firstAppZygoteIsolatedUid", "android.os.Process",
                "FIRST_APP_ZYGOTE_ISOLATED_UID", "Process.FIRST_APP_ZYGOTE_ISOLATED_UID");
        appendIntField(sb, "lastAppZygoteIsolatedUid", "android.os.Process",
                "LAST_APP_ZYGOTE_ISOLATED_UID", "Process.LAST_APP_ZYGOTE_ISOLATED_UID");
        appendIntField(sb, "perUserRange", "android.os.UserHandle",
                "PER_USER_RANGE", "UserHandle.PER_USER_RANGE");
        sb.append('}');

        sb.append(",\"predicates\":[");
        for (int i = 0; i < VALUES.length; i++) {
            int v = VALUES[i];
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"uid\":").append(v);
            sb.append(",\"isApplicationUid\":").append(jsonBool(directIsApplicationUid(v)));
            sb.append(",\"isApp\":").append(jsonBool(invokeBool(mUhIsApp,
                    "UserHandle.isApp", v)));
            sb.append(",\"isIsolated\":").append(jsonBool(invokeBool(mUhIsIsolated,
                    "UserHandle.isIsolated", v)));
            sb.append('}');
        }
        sb.append(']');

        int myUid = Process.myUid();
        sb.append(",\"identity\":{");
        sb.append("\"myUid\":").append(myUid);
        sb.append(",\"userId\":").append(jsonInt(invokeInt(mUhGetUserId,
                "UserHandle.getUserId", myUid)));
        sb.append(",\"appId\":").append(jsonInt(invokeInt(mUhGetAppId,
                "UserHandle.getAppId", myUid)));
        sb.append(",\"isApplicationUid_myUid\":")
                .append(jsonBool(directIsApplicationUid(myUid)));
        sb.append(",\"isIsolatedUid_myUid\":")
                .append(jsonBool(directIsIsolatedUid(myUid)));
        sb.append(",\"isIsolated_noarg\":").append(jsonBool(directIsIsolated()));
        sb.append(",\"isIsolated_int_myUid\":").append(jsonBool(invokeBool(
                mProcIsIsolatedInt, "Process.isIsolated(int)", myUid)));
        sb.append(",\"isApp_myUid\":").append(jsonBool(invokeBool(mUhIsApp,
                "UserHandle.isApp", myUid)));
        sb.append(",\"isIsolated_uh_myUid\":").append(jsonBool(invokeBool(mUhIsIsolated,
                "UserHandle.isIsolated", myUid)));
        sb.append(",\"isCore_myUid\":").append(jsonBool(invokeBool(mUhIsCore,
                "UserHandle.isCore", myUid)));
        sb.append(",\"firstApplicationUid\":").append(Process.FIRST_APPLICATION_UID);
        sb.append(",\"lastApplicationUid\":").append(Process.LAST_APPLICATION_UID);
        sb.append('}');

        sb.append('}');
        return sb.toString();
    }

    // --- reflective surface resolution -------------------------------------

    private void resolveReflectiveSurfaces() {
        mProcIsIsolatedInt = resolveMethod("android.os.Process", "isIsolated",
                "Process.isIsolated(int)", int.class);
        mUhIsApp = resolveMethod("android.os.UserHandle", "isApp",
                "UserHandle.isApp", int.class);
        mUhIsIsolated = resolveMethod("android.os.UserHandle", "isIsolated",
                "UserHandle.isIsolated", int.class);
        mUhIsCore = resolveMethod("android.os.UserHandle", "isCore",
                "UserHandle.isCore", int.class);
        mUhGetAppId = resolveMethod("android.os.UserHandle", "getAppId",
                "UserHandle.getAppId", int.class);
        mUhGetUserId = resolveMethod("android.os.UserHandle", "getUserId",
                "UserHandle.getUserId", int.class);
        // Directly callable public surfaces (recorded after the first use).
        reach.put("Process.myUid", "direct");
        reach.put("Process.FIRST_APPLICATION_UID", "direct");
        reach.put("Process.LAST_APPLICATION_UID", "direct");
    }

    private Method resolveMethod(String cls, String name, String reachKey,
                                 Class<?>... params) {
        try {
            Method m = Class.forName(cls).getMethod(name, params);
            reach.put(reachKey, "reflection");
            return m;
        } catch (Throwable t) {
            reach.put(reachKey, "unreachable: " + describe(t));
            return null;
        }
    }

    private Boolean invokeBool(Method m, String reachKey, int v) {
        if (m == null) {
            return null;
        }
        try {
            return (Boolean) m.invoke(null, Integer.valueOf(v));
        } catch (Throwable t) {
            reach.put(reachKey, "unreachable: invoke " + describe(t));
            return null;
        }
    }

    private Integer invokeInt(Method m, String reachKey, int v) {
        if (m == null) {
            return null;
        }
        try {
            return (Integer) m.invoke(null, Integer.valueOf(v));
        } catch (Throwable t) {
            reach.put(reachKey, "unreachable: invoke " + describe(t));
            return null;
        }
    }

    private void appendIntField(StringBuilder sb, String key, String cls,
                                String field, String reachKey) {
        Integer value = readIntField(cls, field, reachKey);
        sb.append(',').append(jsonString(key)).append(':')
                .append(jsonInt(value));
    }

    private Integer readIntField(String cls, String field, String reachKey) {
        try {
            Field f = Class.forName(cls).getField(field);
            Object o = f.get(null);
            return ((Number) o).intValue();
        } catch (Throwable t) {
            reach.put(reachKey, "unreachable: " + describe(t));
            return null;
        }
    }

    // --- direct public Process surfaces ------------------------------------

    private Boolean directIsApplicationUid(int v) {
        try {
            Boolean r = Process.isApplicationUid(v);
            reach.put("Process.isApplicationUid(int)", "direct");
            return r;
        } catch (Throwable t) {
            reach.put("Process.isApplicationUid(int)", "unreachable: " + describe(t));
            exceptions.add("Process.isApplicationUid(" + v + "): " + describe(t));
            return null;
        }
    }

    private Boolean directIsIsolatedUid(int v) {
        try {
            Boolean r = Process.isIsolatedUid(v);
            reach.put("Process.isIsolatedUid(int)", "direct");
            return r;
        } catch (Throwable t) {
            reach.put("Process.isIsolatedUid(int)", "unreachable: " + describe(t));
            exceptions.add("Process.isIsolatedUid(" + v + "): " + describe(t));
            return null;
        }
    }

    private Boolean directIsIsolated() {
        try {
            Boolean r = Process.isIsolated();
            reach.put("Process.isIsolated()", "direct");
            return r;
        } catch (Throwable t) {
            reach.put("Process.isIsolated()", "unreachable: " + describe(t));
            exceptions.add("Process.isIsolated(): " + describe(t));
            return null;
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getName() + (msg == null ? "" : (": " + msg));
    }

    // --- output ------------------------------------------------------------

    private void writeResults(String json) {
        FileOutputStream out = null;
        try {
            File f = new File(getFilesDir(), "results.json");
            out = new FileOutputStream(f);
            out.write(json.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "failed to persist results.json", t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignore) {
                    // best effort
                }
            }
        }
    }

    private static String jsonBool(Boolean b) {
        return b == null ? "null" : (b.booleanValue() ? "true" : "false");
    }

    private static String jsonInt(Integer i) {
        return i == null ? "null" : Integer.toString(i.intValue());
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static String sha256Hex(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
