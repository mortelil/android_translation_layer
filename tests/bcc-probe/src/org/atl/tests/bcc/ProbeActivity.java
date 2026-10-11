/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.bcc;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-CALLBACKS frozen probe: ComponentCallbacks / ComponentCallbacks2 contract and
 * dispatch.
 *
 * Deterministic phase 1 (runs in onCreate) observes, with fixed inputs:
 *   * the type contract (`instanceof` / `isAssignableFrom`, interface method
 *     presence) for Application, Activity, Context, ContextWrapper;
 *   * the seven {@code TRIM_MEMORY_*} constant values (via reflection);
 *   * registration on {@code getApplicationContext()} (three listeners, no throw,
 *     unregister effect);
 *   * a controlled DIRECT dispatch scenario (S1): Application.onTrimMemory /
 *     onLowMemory / onConfigurationChanged fan-out to the registered listeners,
 *     with a deterministic ordered trace proving registration order, multiple
 *     listeners, unregister effect and the zero-callback drained step.
 *
 * Non-deterministic phase 2: the probe then waits (default 45 s, intent extra
 * {@code wait_ms}) for ONE real platform configuration change while listeners L4
 * and L5 stay registered and {@code Activity.onConfigurationChanged} is
 * overridden. If a change is delivered the trigger state becomes
 * DISPATCH_OBSERVED with the observed ordered trace; otherwise it finalizes as
 * NOT_TRIGGERED. A callback is never fabricated.
 *
 * Protocol (runbook section 8): after phase 1 the probe writes a PENDING
 * results.json and prints {@code BCC_READY}; the runner triggers the platform
 * change and polls for {@code BCC_DONE}. On completion the probe prints one
 * sentinel-wrapped JSON object (BCC_JSON_BEGIN / BCC_JSON_END), persists the same
 * JSON to files/results.json and prints BCC_DONE. Completion is the marker /
 * file, never process exit.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "BCC";
    private static final String TASK_ID = "B-CALLBACKS";
    private static final String SENTINEL_BEGIN = "BCC_JSON_BEGIN";
    private static final String SENTINEL_END = "BCC_JSON_END";
    private static final String DONE_MARKER = "BCC_DONE";
    private static final String READY_MARKER = "BCC_READY";
    private static final String TRIGGER = "uimode_night";

    private static final String[] TRIM_CONSTANTS = {
            "TRIM_MEMORY_COMPLETE",
            "TRIM_MEMORY_MODERATE",
            "TRIM_MEMORY_BACKGROUND",
            "TRIM_MEMORY_UI_HIDDEN",
            "TRIM_MEMORY_RUNNING_CRITICAL",
            "TRIM_MEMORY_RUNNING_LOW",
            "TRIM_MEMORY_RUNNING_MODERATE",
    };

    private final String runId =
            "bcc-apk-" + Long.toHexString(System.currentTimeMillis()) + "-"
                    + Integer.toHexString((int) (Math.random() * 0xffff));

    private final Object lock = new Object();
    private final List<String> exceptions =
            Collections.synchronizedList(new ArrayList<String>());
    private final List<String> directTrace =
            Collections.synchronizedList(new ArrayList<String>());
    private final List<String> platformTrace =
            Collections.synchronizedList(new ArrayList<String>());
    private final Set<String> directThreads =
            Collections.synchronizedSet(new LinkedHashSet<String>());
    private final Map<String, Integer> constants =
            new LinkedHashMap<String, Integer>();

    private volatile String mainThread = "unknown";

    private volatile boolean phase1Done = false;
    private volatile boolean platformObserved = false;
    private int waitMs = 45000;

    // --- type contract -----------------------------------------------------
    private boolean activityInstanceofCC2;
    private boolean applicationInstanceofCC2;
    private boolean applicationContextInstanceofCC2;
    private boolean appAssignableFromCC2;
    private boolean cc2ExtendsCC;
    private boolean ccIsCC2;
    private boolean ccMethodOnConfigurationChanged;
    private boolean ccMethodOnLowMemory;
    private boolean cc2MethodOnConfigurationChanged;
    private boolean cc2MethodOnLowMemory;
    private boolean cc2MethodOnTrimMemory;

    // --- registration ------------------------------------------------------
    private boolean contextRegister;
    private boolean contextUnregister;
    private boolean contextWrapperRegister;
    private boolean contextWrapperUnregister;
    private boolean multipleRegisterThrew;
    private boolean unregisterThrew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainThread = Thread.currentThread().getName();
        try {
            waitMs = getIntent().getIntExtra("wait_ms", 45000);
        } catch (Throwable ignore) {
            // keep the default window
        }
        if (waitMs <= 0) {
            waitMs = 45000;
        }
        String pending;
        try {
            runPhase1();
        } catch (Throwable t) {
            Log.e(TAG, "phase1 failed", t);
            exceptions.add("phase1: " + describe(t));
        }
        phase1Done = true;
        synchronized (lock) {
            pending = renderJson("PENDING");
        }
        writeResults(pending);
        System.out.println(READY_MARKER);
        System.out.flush();
        startWaiter();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!phase1Done) {
            // Direct-dispatch probe calls must not be mistaken for a platform
            // trigger; phase 1 completes before the trigger window opens.
            return;
        }
        synchronized (lock) {
            platformTrace.add("activity:onConfigurationChanged"
                    + ":orientation=" + newConfig.orientation
                    + ":uiMode=" + newConfig.uiMode);
            platformObserved = true;
        }
    }

    // --- phase 1: deterministic contract + direct dispatch -----------------

    private void runPhase1() throws Exception {
        Application app = getApplication();
        Context appCtx = getApplicationContext();

        activityInstanceofCC2 = (this instanceof ComponentCallbacks2);
        applicationInstanceofCC2 = (app != null) && (app instanceof ComponentCallbacks2);
        applicationContextInstanceofCC2 =
                (appCtx != null) && (appCtx instanceof ComponentCallbacks2);
        appAssignableFromCC2 =
                ComponentCallbacks2.class.isAssignableFrom(Application.class);
        cc2ExtendsCC =
                ComponentCallbacks.class.isAssignableFrom(ComponentCallbacks2.class);
        ccIsCC2 =
                ComponentCallbacks2.class.isAssignableFrom(ComponentCallbacks.class);

        ccMethodOnConfigurationChanged = hasMethod(
                ComponentCallbacks.class, "onConfigurationChanged", Configuration.class);
        ccMethodOnLowMemory = hasMethod(ComponentCallbacks.class, "onLowMemory");
        cc2MethodOnConfigurationChanged = hasMethod(
                ComponentCallbacks2.class, "onConfigurationChanged", Configuration.class);
        cc2MethodOnLowMemory = hasMethod(ComponentCallbacks2.class, "onLowMemory");
        cc2MethodOnTrimMemory =
                hasMethod(ComponentCallbacks2.class, "onTrimMemory", int.class);

        contextRegister = hasMethod(
                Context.class, "registerComponentCallbacks", ComponentCallbacks.class);
        contextUnregister = hasMethod(
                Context.class, "unregisterComponentCallbacks", ComponentCallbacks.class);
        contextWrapperRegister = hasMethod(
                ContextWrapper.class, "registerComponentCallbacks", ComponentCallbacks.class);
        contextWrapperUnregister = hasMethod(
                ContextWrapper.class, "unregisterComponentCallbacks", ComponentCallbacks.class);

        for (String name : TRIM_CONSTANTS) {
            Integer value = null;
            try {
                Field f = ComponentCallbacks2.class.getField(name);
                value = Integer.valueOf(f.getInt(null));
            } catch (Throwable t) {
                exceptions.add("constant ComponentCallbacks2." + name + ": " + describe(t));
            }
            constants.put(name, value);
        }

        if (app == null) {
            exceptions.add("getApplication() returned null");
        }
        if (appCtx == null) {
            exceptions.add("getApplicationContext() returned null");
        }
        if (app == null || appCtx == null) {
            return;
        }

        DirectRecorder l1 = new DirectRecorder("L1");
        DirectRecorder l2 = new DirectRecorder("L2");
        DirectRecorder l3 = new DirectRecorder("L3");

        directTrace.add("@register:L1,L2,L3");
        try {
            appCtx.registerComponentCallbacks(l1);
            appCtx.registerComponentCallbacks(l2);
            appCtx.registerComponentCallbacks(l3);
        } catch (Throwable t) {
            multipleRegisterThrew = true;
            exceptions.add("registerComponentCallbacks x3: " + describe(t));
        }

        directTrace.add("@onTrimMemory:40");
        try {
            app.onTrimMemory(40);
        } catch (Throwable t) {
            exceptions.add("onTrimMemory(40): " + describe(t));
        }
        directTrace.add("@onTrimMemory:10");
        try {
            app.onTrimMemory(10);
        } catch (Throwable t) {
            exceptions.add("onTrimMemory(10): " + describe(t));
        }
        directTrace.add("@onLowMemory");
        try {
            app.onLowMemory();
        } catch (Throwable t) {
            exceptions.add("onLowMemory(): " + describe(t));
        }

        Configuration cfg = new Configuration();
        cfg.orientation = Configuration.ORIENTATION_LANDSCAPE;
        cfg.uiMode = 0x20;
        directTrace.add("@onConfigurationChanged:orientation="
                + cfg.orientation + ":uiMode=" + cfg.uiMode);
        try {
            app.onConfigurationChanged(cfg);
        } catch (Throwable t) {
            exceptions.add("onConfigurationChanged(Configuration): " + describe(t));
        }

        directTrace.add("@unregister:L2");
        try {
            appCtx.unregisterComponentCallbacks(l2);
        } catch (Throwable t) {
            unregisterThrew = true;
            exceptions.add("unregisterComponentCallbacks(L2): " + describe(t));
        }

        directTrace.add("@onTrimMemory:60");
        try {
            app.onTrimMemory(60);
        } catch (Throwable t) {
            exceptions.add("onTrimMemory(60): " + describe(t));
        }

        directTrace.add("@unregister:L1,L3");
        try {
            appCtx.unregisterComponentCallbacks(l1);
            appCtx.unregisterComponentCallbacks(l3);
        } catch (Throwable t) {
            unregisterThrew = true;
            exceptions.add("unregisterComponentCallbacks(L1,L3): " + describe(t));
        }

        directTrace.add("@onTrimMemory:80");
        try {
            app.onTrimMemory(80);
        } catch (Throwable t) {
            exceptions.add("onTrimMemory(80): " + describe(t));
        }

        // Platform-trigger listeners stay registered for the phase-2 window.
        try {
            appCtx.registerComponentCallbacks(new PlatformRecorder("L4"));
            appCtx.registerComponentCallbacks(new PlatformRecorder("L5"));
        } catch (Throwable t) {
            exceptions.add("platform registerComponentCallbacks(L4,L5): " + describe(t));
        }
    }

    private void startWaiter() {
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + waitMs;
                while (System.currentTimeMillis() < deadline && !platformObserved) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                String state = platformObserved ? "DISPATCH_OBSERVED" : "NOT_TRIGGERED";
                String json;
                synchronized (lock) {
                    json = renderJson(state);
                }
                writeResults(json);
                System.out.println(SENTINEL_BEGIN);
                System.out.println(json);
                System.out.println(SENTINEL_END);
                System.out.println(DONE_MARKER);
                System.out.flush();
            }
        }, "bcc-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    // --- listeners ---------------------------------------------------------

    private static String configTag(Configuration c) {
        return "orientation=" + c.orientation + ":uiMode=" + c.uiMode;
    }

    private final class DirectRecorder implements ComponentCallbacks2 {
        private final String id;

        DirectRecorder(String id) {
            this.id = id;
        }

        private void mark() {
            directThreads.add(Thread.currentThread().getName());
        }

        @Override
        public void onTrimMemory(int level) {
            synchronized (lock) {
                mark();
                directTrace.add(id + ":onTrimMemory:" + level);
            }
        }

        @Override
        public void onLowMemory() {
            synchronized (lock) {
                mark();
                directTrace.add(id + ":onLowMemory");
            }
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            synchronized (lock) {
                mark();
                directTrace.add(id + ":onConfigurationChanged:" + configTag(newConfig));
            }
        }
    }

    private final class PlatformRecorder implements ComponentCallbacks2 {
        private final String id;

        PlatformRecorder(String id) {
            this.id = id;
        }

        @Override
        public void onTrimMemory(int level) {
            synchronized (lock) {
                platformTrace.add(id + ":onTrimMemory:" + level);
                platformObserved = true;
            }
        }

        @Override
        public void onLowMemory() {
            synchronized (lock) {
                platformTrace.add(id + ":onLowMemory");
                platformObserved = true;
            }
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            synchronized (lock) {
                platformTrace.add(id + ":onConfigurationChanged:" + configTag(newConfig));
                platformObserved = true;
            }
        }
    }

    // --- reflection helpers ------------------------------------------------

    private static boolean hasMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            cls.getMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getName() + (msg == null ? "" : (": " + msg));
    }

    // --- JSON rendering ----------------------------------------------------

    private String renderJson(String platformState) {
        int targetSdk = -1;
        int minSdk = -1;
        String apkSha = null;
        try {
            targetSdk = getApplicationInfo().targetSdkVersion;
            minSdk = getApplicationInfo().minSdkVersion;
        } catch (Throwable t) {
            exceptions.add("applicationInfo: " + describe(t));
        }
        try {
            apkSha = sha256Hex(new File(getPackageCodePath()));
        } catch (Throwable t) {
            exceptions.add("apk sha256: " + describe(t));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"thread\":").append(jsonString(mainThread));
        sb.append(",\"status\":\"").append(exceptions.isEmpty() ? "OK" : "ERROR");
        sb.append("\",\"exceptions\":[");
        for (int i = 0; i < exceptions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(exceptions.get(i)));
        }
        sb.append(']');

        sb.append(",\"config\":{\"trigger\":").append(jsonString(TRIGGER));
        sb.append(",\"waitMs\":").append(waitMs);
        sb.append(",\"targetSdk\":").append(targetSdk);
        sb.append(",\"minSdk\":").append(minSdk);
        sb.append('}');

        sb.append(",\"completion\":{\"marker\":").append(jsonString(DONE_MARKER));
        sb.append(",\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END));
        sb.append(",\"readyMarker\":").append(jsonString(READY_MARKER)).append('}');

        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');

        sb.append(",\"type_contract\":{");
        sb.append("\"activityInstanceofCC2\":").append(activityInstanceofCC2);
        sb.append(",\"applicationInstanceofCC2\":").append(applicationInstanceofCC2);
        sb.append(",\"applicationContextInstanceofCC2\":")
                .append(applicationContextInstanceofCC2);
        sb.append(",\"applicationClassAssignableFromCC2\":").append(appAssignableFromCC2);
        sb.append(",\"cc2ExtendsCC\":").append(cc2ExtendsCC);
        sb.append(",\"ccIsCC2\":").append(ccIsCC2);
        sb.append(",\"methods\":{");
        sb.append("\"cc.onConfigurationChanged\":").append(ccMethodOnConfigurationChanged);
        sb.append(",\"cc.onLowMemory\":").append(ccMethodOnLowMemory);
        sb.append(",\"cc2.onConfigurationChanged\":").append(cc2MethodOnConfigurationChanged);
        sb.append(",\"cc2.onLowMemory\":").append(cc2MethodOnLowMemory);
        sb.append(",\"cc2.onTrimMemory\":").append(cc2MethodOnTrimMemory);
        sb.append("}}");

        sb.append(",\"constants\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : constants.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonString(e.getKey())).append(':').append(jsonInt(e.getValue()));
        }
        sb.append('}');

        sb.append(",\"registration\":{");
        sb.append("\"contextRegister\":").append(contextRegister);
        sb.append(",\"contextUnregister\":").append(contextUnregister);
        sb.append(",\"contextWrapperRegister\":").append(contextWrapperRegister);
        sb.append(",\"contextWrapperUnregister\":").append(contextWrapperUnregister);
        sb.append(",\"multipleRegisterThrew\":").append(multipleRegisterThrew);
        sb.append(",\"unregisterThrew\":").append(unregisterThrew);
        sb.append(",\"registerTarget\":\"getApplicationContext\"}");

        sb.append(",\"direct_dispatch\":{\"state\":\"DISPATCH_OBSERVED\"");
        sb.append(",\"scenario\":\"S1\",\"threads\":[");
        List<String> threads;
        synchronized (directThreads) {
            threads = new ArrayList<String>(directThreads);
        }
        Collections.sort(threads);
        for (int i = 0; i < threads.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(threads.get(i)));
        }
        sb.append("],\"trace\":[");
        appendStrings(sb, directTrace);
        sb.append("]}");

        sb.append(",\"platform_trigger\":{\"state\":").append(jsonString(platformState));
        sb.append(",\"trigger\":").append(jsonString(TRIGGER));
        sb.append(",\"waitedMs\":").append(waitMs);
        sb.append(",\"trace\":[");
        appendStrings(sb, platformTrace);
        sb.append("]}}");

        return sb.toString();
    }

    private static void appendStrings(StringBuilder sb, List<String> values) {
        List<String> copy;
        synchronized (values) {
            copy = new ArrayList<String>(values);
        }
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(copy.get(i)));
        }
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
        java.security.MessageDigest md =
                java.security.MessageDigest.getInstance("SHA-256");
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
