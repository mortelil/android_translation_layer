/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.sdk;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * D-SDK probe: observe the runtime SDK/API profile, version-qualified resource
 * selection and {@code Build.getSerial()} semantics for ONE frozen APK.
 *
 * Emits a single sentinel-wrapped JSON object (SDKPROBE_JSON_BEGIN/_END) on
 * stdout, persists the same JSON to files/results.json, and finally prints the
 * completion marker SDKPROBE_DONE. Completion = the marker / results.json, never
 * process exit (ATL GUI apps do not self-exit; see runbook section 8).
 *
 * The class is deliberately compiled against the public SDK android.jar only
 * (SystemProperties is read reflectively) so ONE binary runs on both Android and
 * ATL. It records what the platform ACTUALLY returns; nothing is fabricated.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "SDKPROBE";
    private static final String TASK_ID = "D-SDK";
    private static final String PACKAGE = "org.atl.tests.sdk";

    private static final String SENTINEL_BEGIN = "SDKPROBE_JSON_BEGIN";
    private static final String SENTINEL_END = "SDKPROBE_JSON_END";
    private static final String DONE_MARKER = "SDKPROBE_DONE";

    private static final String SENTINEL_BASE = "sentinel-base";
    private static final String SENTINEL_V21 = "sentinel-v21";
    private static final String SENTINEL_V28 = "sentinel-v28";

    private static final String PERM_PHONE_STATE = "android.permission.READ_PHONE_STATE";
    private static final String PERM_PRIV_PHONE_STATE =
            "android.permission.READ_PRIVILEGED_PHONE_STATE";

    private final String runId = "sdk-probe-" + Long.toHexString(System.currentTimeMillis());

    /** Top-level unexpected errors (structure/resource failures), not getSerial. */
    private final StringBuilder exceptions = new StringBuilder();

    /** Non-fatal observation gaps (e.g. hidden-API block), keyed by field. */
    private final StringBuilder readErrors = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json;
        try {
            json = runProbe();
        } catch (Throwable t) {
            appendException(t);
            json = buildJson();
        }
        emit(json);
        Log.i(TAG, DONE_MARKER);
        System.out.println(DONE_MARKER);
        System.out.flush();
    }

    // ---- probe body -------------------------------------------------------

    private String runProbe() {
        // Read the whole VERSION profile reflectively so a field that a runtime
        // does not implement is recorded as an observation gap (read_error)
        // instead of aborting the probe with NoSuchFieldError.
        Integer sdkInt = readIntField("SDK_INT");
        String sdk = readStringField("SDK");
        String release = readStringField("RELEASE");
        String codename = readStringField("SDK_CODENAME");
        Integer resourcesSdkInt = readIntField("RESOURCES_SDK_INT");
        Integer previewSdkInt = readIntField("PREVIEW_SDK_INT");
        if (sdkInt == null) {
            appendException(new IllegalStateException("Build.VERSION.SDK_INT unreadable"));
        }

        String propSdk = readProperty("ro.build.version.sdk");
        String propCodename = readProperty("ro.build.version.codename");
        String propRelease = readProperty("ro.build.version.release");

        int targetSdk = -1;
        int minSdk = -1;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(PACKAGE, 0);
            targetSdk = ai.targetSdkVersion;
            minSdk = ai.minSdkVersion;
        } catch (Throwable t) {
            appendException(t);
        }

        String selected;
        String selectedValue;
        try {
            selectedValue = getString(R.string.sdk_sentinel);
        } catch (Throwable t) {
            appendException(t);
            selectedValue = null;
        }
        if (SENTINEL_V28.equals(selectedValue)) {
            selected = "v28";
        } else if (SENTINEL_V21.equals(selectedValue)) {
            selected = "v21";
        } else if (SENTINEL_BASE.equals(selectedValue)) {
            selected = "base";
        } else {
            selected = "unknown";
        }

        int configSdk = -1;
        try {
            Configuration cfg = getResources().getConfiguration();
            // sdkVersion is a hidden/public-but-unsupported field; read it
            // reflectively so a runtime without it is an observation gap.
            java.lang.reflect.Field f = Configuration.class.getField("sdkVersion");
            configSdk = f.getInt(cfg);
        } catch (Throwable t) {
            noteReadError("Configuration.sdkVersion", t);
        }

        // Permission state as the platform reports it for THIS process.
        Boolean phoneState = null;
        Boolean privPhoneState = null;
        boolean permissionsAvailable = true;
        try {
            PackageManager pm = getPackageManager();
            phoneState = pm.checkPermission(PERM_PHONE_STATE, PACKAGE)
                    == PackageManager.PERMISSION_GRANTED;
            privPhoneState = pm.checkPermission(PERM_PRIV_PHONE_STATE, PACKAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            permissionsAvailable = false;
            noteReadError("permissions", t);
        }
        if (phoneState == null) {
            phoneState = Boolean.FALSE;
        }
        if (privPhoneState == null) {
            privPhoneState = Boolean.FALSE;
        }

        // getSerial observation (the permission matrix per AOSP
        // DeviceIdentifiersPolicy: privileged -> serial; pre-Q + READ_PHONE_STATE
        // -> "unknown"; otherwise SecurityException).
        String serialValue = null;
        String serialException = null;
        String serialMessage = null;
        try {
            Method m = Build.class.getMethod("getSerial");
            Object v = m.invoke(null);
            serialValue = (v == null) ? null : String.valueOf(v);
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            serialException = cause.getClass().getName();
            serialMessage = String.valueOf(cause.getMessage());
        }
        String classification;
        if (serialException != null) {
            classification = "java.lang.SecurityException".equals(serialException)
                    ? "SECURITY_EXCEPTION" : "OTHER";
        } else if (serialValue != null && "unknown".equalsIgnoreCase(serialValue.trim())) {
            classification = "UNKNOWN";
        } else {
            // A real/other value (including null/empty) is NOT the AOSP unknown
            // marker; report it honestly rather than pretending.
            classification = "OTHER";
        }

        this.obs = new Obs(sdkInt == null ? -1 : sdkInt, sdk, release, codename,
                resourcesSdkInt == null ? -1 : resourcesSdkInt,
                previewSdkInt == null ? -1 : previewSdkInt,
                propSdk, propCodename, propRelease, targetSdk, minSdk, selected,
                selectedValue, configSdk, permissionsAvailable, phoneState, privPhoneState,
                serialValue, serialException, serialMessage, classification);
        return buildJson();
    }

    private static final class Obs {
        final int sdkInt;
        final String sdk, release, codename;
        final int resourcesSdkInt, previewSdkInt;
        final String propSdk, propCodename, propRelease;
        final int targetSdk, minSdk;
        final String selected, selectedValue;
        final int configSdk;
        final boolean permissionsAvailable;
        final Boolean phoneState, privPhoneState;
        final String serialValue, serialException, serialMessage, classification;

        Obs(int sdkInt, String sdk, String release, String codename, int resourcesSdkInt,
            int previewSdkInt, String propSdk, String propCodename, String propRelease,
            int targetSdk, int minSdk, String selected, String selectedValue, int configSdk,
            boolean permissionsAvailable, Boolean phoneState, Boolean privPhoneState,
            String serialValue, String serialException, String serialMessage,
            String classification) {
            this.sdkInt = sdkInt;
            this.sdk = sdk;
            this.release = release;
            this.codename = codename;
            this.resourcesSdkInt = resourcesSdkInt;
            this.previewSdkInt = previewSdkInt;
            this.propSdk = propSdk;
            this.propCodename = propCodename;
            this.propRelease = propRelease;
            this.targetSdk = targetSdk;
            this.minSdk = minSdk;
            this.selected = selected;
            this.selectedValue = selectedValue;
            this.configSdk = configSdk;
            this.permissionsAvailable = permissionsAvailable;
            this.phoneState = phoneState;
            this.privPhoneState = privPhoneState;
            this.serialValue = serialValue;
            this.serialException = serialException;
            this.serialMessage = serialMessage;
            this.classification = classification;
        }
    }

    private Obs obs;

    // ---- platform helpers -------------------------------------------------

    private String readProperty(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method m = cls.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return (v == null) ? null : String.valueOf(v);
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            noteReadError("SystemProperties.get(" + key + ")", cause);
            return null;
        }
    }

    private Object readField(String name) {
        try {
            java.lang.reflect.Field f = Build.VERSION.class.getField(name);
            return f.get(null);
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            noteReadError("Build.VERSION." + name, cause);
            return null;
        }
    }

    private Integer readIntField(String name) {
        Object v = readField(name);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            noteReadError("Build.VERSION." + name + " (not an int)",
                    new ClassCastException(String.valueOf(v)));
        }
        return null;
    }

    private String readStringField(String name) {
        Object v = readField(name);
        return (v == null) ? null : String.valueOf(v);
    }

    /** Append one read-error object, comma-separating as a JSON array. */
    private void noteReadError(String where, Throwable t) {
        if (readErrors.length() > 0) {
            readErrors.append(',');
        }
        readErrors.append(errJsonEntry(where, t));
    }

    // ---- JSON -------------------------------------------------------------

    private String buildJson() {
        Obs o = obs;
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"task_id\":").append(jsonString(TASK_ID)).append(',');
        sb.append("\"probe\":\"sdk-profile\",");
        sb.append("\"run_id\":").append(jsonString(runId)).append(',');
        sb.append("\"run_kind\":").append(jsonString(runKind())).append(',');
        sb.append("\"scenario\":").append(jsonString(scenario())).append(',');
        sb.append("\"status\":").append(jsonString(exceptions.length() == 0 ? "OK" : "ERROR"))
                .append(',');
        sb.append("\"exceptions\":").append(exceptionsJson()).append(',');
        sb.append("\"apk\":{");
        sb.append("\"sha256\":null,");
        sb.append("\"package\":").append(jsonString(PACKAGE)).append(',');
        sb.append("\"min_sdk\":").append(o == null ? -1 : o.minSdk).append(',');
        sb.append("\"target_sdk\":").append(o == null ? -1 : o.targetSdk);
        sb.append("},");
        sb.append("\"build_version\":{");
        sb.append("\"SDK_INT\":").append(o == null ? -1 : o.sdkInt).append(',');
        sb.append("\"SDK\":").append(o == null ? "null" : jsonString(o.sdk)).append(',');
        sb.append("\"RELEASE\":").append(o == null ? "null" : jsonString(o.release)).append(',');
        sb.append("\"SDK_CODENAME\":")
                .append(o == null ? "null" : jsonString(o.codename)).append(',');
        sb.append("\"RESOURCES_SDK_INT\":")
                .append(o == null ? -1 : o.resourcesSdkInt).append(',');
        sb.append("\"PREVIEW_SDK_INT\":")
                .append(o == null ? -1 : o.previewSdkInt);
        sb.append("},");
        sb.append("\"system_properties\":{");
        sb.append("\"ro.build.version.sdk\":")
                .append(o == null || o.propSdk == null ? "null" : jsonString(o.propSdk)).append(',');
        sb.append("\"ro.build.version.codename\":")
                .append(o == null || o.propCodename == null ? "null"
                        : jsonString(o.propCodename)).append(',');
        sb.append("\"ro.build.version.release\":")
                .append(o == null || o.propRelease == null ? "null"
                        : jsonString(o.propRelease)).append(',');
        sb.append("\"read_errors\":[").append(readErrors).append(']');
        sb.append("},");
        sb.append("\"resource_selection\":{");
        String sel = o == null ? "unknown" : o.selected;
        String selVal = o == null ? null : o.selectedValue;
        sb.append("\"selected\":").append(jsonString(sel)).append(',');
        sb.append("\"value\":").append(selVal == null ? "null" : jsonString(selVal)).append(',');
        sb.append("\"candidates\":{");
        sb.append("\"base\":").append(jsonString(SENTINEL_BASE)).append(',');
        sb.append("\"v21\":").append(jsonString(SENTINEL_V21)).append(',');
        sb.append("\"v28\":").append(jsonString(SENTINEL_V28));
        sb.append("},");
        sb.append("\"expected_for_level\":")
                .append(jsonString(o == null ? "unknown" : expectedQualifier(o.sdkInt))).append(',');
        sb.append("\"agrees\":")
                .append(o != null && sel.equals(expectedQualifier(o.sdkInt)) ? "true" : "false");
        sb.append("},");
        sb.append("\"configuration\":{");
        sb.append("\"sdkVersion\":").append(o == null ? -1 : o.configSdk);
        sb.append("},");
        sb.append("\"permissions\":{");
        sb.append("\"available\":")
                .append(o != null && o.permissionsAvailable ? "true" : "false").append(',');
        sb.append("\"READ_PHONE_STATE\":")
                .append(o != null && Boolean.TRUE.equals(o.phoneState) ? "true" : "false")
                .append(',');
        sb.append("\"READ_PRIVILEGED_PHONE_STATE\":")
                .append(o != null && Boolean.TRUE.equals(o.privPhoneState) ? "true" : "false");
        sb.append("},");
        sb.append("\"get_serial\":{");
        sb.append("\"attempted\":true,");
        sb.append("\"value\":")
                .append(o == null || o.serialValue == null ? "null"
                        : jsonString(o.serialValue)).append(',');
        sb.append("\"exception\":")
                .append(o == null || o.serialException == null ? "null"
                        : jsonString(o.serialException)).append(',');
        sb.append("\"message\":")
                .append(o == null || o.serialMessage == null ? "null"
                        : jsonString(o.serialMessage)).append(',');
        sb.append("\"classification\":")
                .append(jsonString(o == null ? "OTHER" : o.classification));
        sb.append('}');
        sb.append('}');
        return sb.toString();
    }

    private static String expectedQualifier(int level) {
        if (level >= 28) {
            return "v28";
        }
        if (level >= 21) {
            return "v21";
        }
        return "base";
    }

    private String runKind() {
        String v = extraFirst("run_kind");
        if ("android".equals(v) || "atl-default".equals(v) || "atl-override".equals(v)) {
            return v;
        }
        if (v == null) {
            v = System.getenv("SDKPROBE_RUN_KIND");
        }
        if ("android".equals(v) || "atl-default".equals(v) || "atl-override".equals(v)) {
            return v;
        }
        // No marker (Android am start without extras / ATL GUI): the family
        // manifest records the role; an unmatched value is rejected by the
        // comparator so a wrong role fails closed rather than passing silently.
        return "unknown";
    }

    private String scenario() {
        String v = extraFirst("scenario");
        if (v == null || v.isEmpty()) {
            v = System.getenv("SDKPROBE_SCENARIO");
        }
        return (v == null || v.isEmpty()) ? "default" : v;
    }

    /** Intent extra first (Android reference), then env (ATL container). */
    private String extraFirst(String key) {
        try {
            android.content.Intent it = getIntent();
            if (it != null) {
                String v = it.getStringExtra(key);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable t) {
            noteReadError("intent." + key, t);
        }
        return null;
    }

    private String exceptionsJson() {
        if (exceptions.length() == 0) {
            return "[]";
        }
        return "[" + exceptions + "]";
    }

    private static String errJsonEntry(String where, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"where\":").append(jsonString(where)).append(',');
        sb.append("\"exception\":").append(jsonString(t.getClass().getName())).append(',');
        sb.append("\"message\":").append(jsonString(String.valueOf(t.getMessage())));
        sb.append('}');
        return sb.toString();
    }

    private void appendException(Throwable t) {
        if (exceptions.length() > 0) {
            exceptions.append(',');
        }
        exceptions.append(errJsonEntry("probe", t));
    }

    private void emit(String json) {
        String block = SENTINEL_BEGIN + "\n" + json + "\n" + SENTINEL_END;
        System.out.println(block);
        System.out.flush();
        writeResults(json);
    }

    private void writeResults(String json) {
        File dir = getFilesDir();
        if (dir == null) {
            return;
        }
        File out = new File(dir, "results.json");
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                os.write(json.getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not write " + out + ": " + t);
        }
    }

    static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
