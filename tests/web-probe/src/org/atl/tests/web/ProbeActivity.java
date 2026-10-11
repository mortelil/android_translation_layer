/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.web;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * C-WEB probe (APP-COMPAT case C).
 *
 * One frozen APK hosts a WebView, loads a controlled local page
 * (assets/probe.html via loadDataWithBaseURL) with two text fields and
 * observes, on Android and on ATL:
 *
 *   - getter/setter round-trip parity of the implemented WebSettings subset;
 *   - the real user-agent string (never a fixed fake identifier);
 *   - the NOT_SUPPORTED settings' observed behaviour (explicit, no invention);
 *   - scenario A setNeedInitialFocus(true) vs B setNeedInitialFocus(false):
 *     which element is focused after load (document.activeElement from JS),
 *     whether field1 is editable/focusable, and the IME-request observation.
 *
 * Emits a single sentinel-wrapped JSON object (WEBPROBE_JSON_BEGIN/_END) on
 * stdout, persists the same JSON to files/results.json, then prints the
 * completion marker WEBPROBE_DONE. Completion is the marker/results.json,
 * never process exit (runbook section 8). Run ID is unique per run.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "WEBPROBE";
    private static final String TASK_ID = "C-WEB";
    private static final String BASE_URL = "https://probe.local/";
    private static final String ASSET_PAGE = "probe.html";
    private static final String CUSTOM_UA = "ATL-WEB-PROBE/1.0";
    private static final String FAKE_UA = "GDPR VIOLATION";
    private static final String GATE_VAR = "ATL_UGLY_ENABLE_WEBVIEW";
    private static final String SENTINEL_BEGIN = "WEBPROBE_JSON_BEGIN";
    private static final String SENTINEL_END = "WEBPROBE_JSON_END";
    private static final String DONE_MARKER = "WEBPROBE_DONE";
    private static final int SCENARIO_TIMEOUT_MS = 20000;
    private static final int IME_DELAY_MS = 1500;

    private static final String[] SCEN_IDS = {"A", "B"};
    private static final boolean[] SCEN_NIF = {true, false};

    private static final String[] IMPLEMENTED = {
            "JavaScriptEnabled",
            "DomStorageEnabled",
            "DatabaseEnabled",
            "LoadsImagesAutomatically",
            "MediaPlaybackRequiresUserGesture",
            "JavaScriptCanOpenWindowsAutomatically",
            "AllowFileAccessFromFileURLs",
            "AllowUniversalAccessFromFileURLs",
    };

    private static final String[] NOT_SUPPORTED = {
            "AllowFileAccess",
            "BlockNetworkLoads",
            "BlockNetworkImage",
            "SupportMultipleWindows",
            "AppCacheEnabled",
            "AppCachePath",
            "AppCacheMaxSize",
    };

    private static final String PAGE_JS =
            "(function(){var ae=document.activeElement||null;"
            + "var f1=document.getElementById('field1');"
            + "var f2=document.getElementById('field2');"
            + "function e(x){return x&&!x.disabled&&x.type!=='hidden'?'1':'0';}"
            + "return [(ae&&ae.id)?ae.id:'',ae?ae.tagName:'',"
            + "document.hasFocus()?'1':'0',e(f1),e(f2),"
            + "f1?String(f1.tabIndex):'-1'].join('|');})()";

    private final String runId = "web-probe-" + Long.toHexString(System.currentTimeMillis())
            + "-" + Integer.toHexString((int) (Math.random() * 0xffff));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ScenarioObs> scenarios = new ArrayList<ScenarioObs>();
    private final List<SettingObs> settings = new ArrayList<SettingObs>();
    private final List<NotSupportedObs> notSupported = new ArrayList<NotSupportedObs>();
    private final UaObs ua = new UaObs();

    private WebView webView;
    private WebSettings webSettings;
    private String pageHtml = "";
    private String pageError = null;

    private ScenarioObs scObs;
    private boolean scFinished;
    private boolean scEvaluated;
    private Runnable scOnDone;

    private final Runnable scFinalize = new Runnable() {
        public void run() { finalizeScenario(); }
    };
    private final Runnable scTimeout = new Runnable() {
        public void run() {
            if (!scFinished && scObs != null) {
                scObs.observationSource = "timeout";
                scFinalize.run();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            runProbe();
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            emitStartupError(t);
        }
    }

    private void emitStartupError(Throwable t) {
        String detail = t.getClass().getName() + ": " + t.getMessage();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"status\":\"ERROR\"");
        sb.append(",\"exceptions\":[").append(jsonString(detail)).append(']');
        sb.append(",\"error\":").append(jsonString(detail)).append('}');
        emit(sb.toString());
    }

    private void emit(String json) {
        System.out.println(SENTINEL_BEGIN);
        System.out.println(json);
        System.out.println(SENTINEL_END);
        writeResults(json);
        System.out.println(DONE_MARKER);
    }

    private void runProbe() throws Exception {
        pageHtml = readAsset(ASSET_PAGE);

        webView = new WebView(this);
        webView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);
        webSettings = webView.getSettings();

        // The Activity window may be in touch mode, where no view receives focus
        // automatically; without view focus setNeedInitialFocus has nothing to act
        // on. Request focus on the WebView identically for both scenarios so the
        // only variable left is needInitialFocus itself.
        try {
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        } catch (Throwable ignored) {
        }

        collectSettings();
        collectUserAgent();
        collectNotSupported();

        scOnDone = new Runnable() {
            public void run() { startScenario(1); }
        };
        startScenario(0);
    }

    private void startScenario(final int index) {
        scObs = new ScenarioObs();
        scObs.id = SCEN_IDS[index];
        scObs.needInitialFocusSet = SCEN_NIF[index];
        scObs.observedOnMainThread = (Looper.myLooper() == Looper.getMainLooper());
        scenarios.add(scObs);
        scFinished = false;
        scEvaluated = false;
        try {
            webSettings.setNeedInitialFocus(scObs.needInitialFocusSet);
        } catch (Throwable t) {
            scObs.error = "setNeedInitialFocus: " + t;
        }
        try {
            webSettings.setJavaScriptEnabled(true);
        } catch (Throwable ignored) {
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (scObs == null || scObs.id != SCEN_IDS[index]) {
                    return;
                }
                scObs.pageLoaded = true;
                if (scEvaluated) {
                    return;
                }
                scEvaluated = true;
                final WebView probeView = view;
                // Let any initial-focus handling settle before sampling the DOM.
                handler.postDelayed(new Runnable() {
                    public void run() {
                        if (scFinished) {
                            return;
                        }
                        try {
                            probeView.evaluateJavascript(PAGE_JS, new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    parseJs(scObs, value);
                                    scFinalize.run();
                                }
                            });
                        } catch (Throwable t) {
                            scObs.jsError = String.valueOf(t);
                            scFinalize.run();
                        }
                    }
                }, IME_DELAY_MS);
            }
        });
        handler.postDelayed(scTimeout, SCENARIO_TIMEOUT_MS);
        webView.loadDataWithBaseURL(BASE_URL, pageHtml, "text/html", "utf-8", null);
    }

    private void finalizeScenario() {
        if (scFinished) {
            return;
        }
        scFinished = true;
        handler.removeCallbacks(scTimeout);
        try {
            scObs.webViewFocused = webView.isFocused();
        } catch (Throwable ignored) {
        }
        captureIme(scObs);
        Runnable next = scOnDone;
        scOnDone = null;
        if (next != null) {
            next.run();
        } else {
            finishAll();
        }
    }

    private void captureIme(ScenarioObs obs) {
        try {
            Rect r = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            int rootH = webView.getRootView().getHeight();
            obs.imeRequested = (rootH - r.bottom) > (rootH / 6);
        } catch (Throwable t) {
            obs.imeRequested = null;
        }
    }

    private void parseJs(ScenarioObs obs, String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        String[] p = v.split("\\|", -1);
        if (p.length < 6) {
            obs.jsError = "unexpected JS payload: " + value;
            return;
        }
        obs.jsObserved = true;
        obs.activeId = p[0];
        obs.activeTag = p[1];
        obs.documentHasFocus = "1".equals(p[2]);
        obs.firstFieldEditable = "1".equals(p[3]);
        obs.secondFieldEditable = "1".equals(p[4]);
        try {
            obs.field1TabIndex = Integer.valueOf(p[5]);
        } catch (NumberFormatException e) {
            obs.field1TabIndex = null;
        }
        obs.observationSource = "evaluateJavascript";
    }

    private void collectSettings() {
        final WebSettings s = webSettings;
        List<BoolSetting> list = new ArrayList<BoolSetting>();
        list.add(new BoolSetting("JavaScriptEnabled") {
            boolean get() { return s.getJavaScriptEnabled(); }
            void set(boolean v) { s.setJavaScriptEnabled(v); }
        });
        list.add(new BoolSetting("DomStorageEnabled") {
            boolean get() { return s.getDomStorageEnabled(); }
            void set(boolean v) { s.setDomStorageEnabled(v); }
        });
        list.add(new BoolSetting("DatabaseEnabled") {
            boolean get() { return s.getDatabaseEnabled(); }
            void set(boolean v) { s.setDatabaseEnabled(v); }
        });
        list.add(new BoolSetting("LoadsImagesAutomatically") {
            boolean get() { return s.getLoadsImagesAutomatically(); }
            void set(boolean v) { s.setLoadsImagesAutomatically(v); }
        });
        list.add(new BoolSetting("MediaPlaybackRequiresUserGesture") {
            boolean get() { return s.getMediaPlaybackRequiresUserGesture(); }
            void set(boolean v) { s.setMediaPlaybackRequiresUserGesture(v); }
        });
        list.add(new BoolSetting("JavaScriptCanOpenWindowsAutomatically") {
            boolean get() { return s.getJavaScriptCanOpenWindowsAutomatically(); }
            void set(boolean v) { s.setJavaScriptCanOpenWindowsAutomatically(v); }
        });
        list.add(new BoolSetting("AllowFileAccessFromFileURLs") {
            boolean get() { return s.getAllowFileAccessFromFileURLs(); }
            void set(boolean v) { s.setAllowFileAccessFromFileURLs(v); }
        });
        list.add(new BoolSetting("AllowUniversalAccessFromFileURLs") {
            boolean get() { return s.getAllowUniversalAccessFromFileURLs(); }
            void set(boolean v) { s.setAllowUniversalAccessFromFileURLs(v); }
        });
        for (BoolSetting b : list) {
            SettingObs o = new SettingObs(b.name);
            try {
                boolean def = b.get();
                b.set(true);
                boolean tv = b.get();
                b.set(false);
                boolean fv = b.get();
                b.set(def);
                o.observable = true;
                o.defaultValue = def;
                o.setTrueGet = tv;
                o.setFalseGet = fv;
                o.roundTrip = tv && !fv;
            } catch (Throwable t) {
                o.observable = false;
                o.error = t.getClass().getName() + ": " + t.getMessage();
            }
            settings.add(o);
        }
    }

    private void collectUserAgent() {
        try {
            ua.getDefaultUserAgent = WebSettings.getDefaultUserAgent(this);
            ua.userAgentDefault = webSettings.getUserAgentString();
            webSettings.setUserAgentString(CUSTOM_UA);
            ua.userAgentAfterSet = webSettings.getUserAgentString();
            ua.userAgentRoundTrip = CUSTOM_UA.equals(ua.userAgentAfterSet);
            webSettings.setUserAgentString(ua.userAgentDefault);
            ua.observable = true;
        } catch (Throwable t) {
            ua.observable = false;
            ua.error = t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private void collectNotSupported() {
        NotSupportedObs o;
        o = new NotSupportedObs("AllowFileAccess");
        try {
            webSettings.setAllowFileAccess(false);
            o.setValue = Boolean.FALSE;
            o.getValue = Boolean.valueOf(webSettings.getAllowFileAccess());
        } catch (Throwable t) {
            o.getValue = "ERROR: " + t;
        }
        notSupported.add(o);

        o = new NotSupportedObs("BlockNetworkLoads");
        try {
            webSettings.setBlockNetworkLoads(true);
            o.setValue = Boolean.TRUE;
            o.getValue = Boolean.valueOf(webSettings.getBlockNetworkLoads());
        } catch (Throwable t) {
            o.getValue = "ERROR: " + t;
        }
        notSupported.add(o);

        o = new NotSupportedObs("BlockNetworkImage");
        try {
            webSettings.setBlockNetworkImage(true);
            o.setValue = Boolean.TRUE;
            o.getValue = Boolean.valueOf(webSettings.getBlockNetworkImage());
        } catch (Throwable t) {
            o.getValue = "ERROR: " + t;
        }
        notSupported.add(o);

        o = new NotSupportedObs("SupportMultipleWindows");
        try {
            webSettings.setSupportMultipleWindows(true);
            o.setValue = Boolean.TRUE;
            o.getValue = Boolean.valueOf(webSettings.supportMultipleWindows());
        } catch (Throwable t) {
            o.getValue = "ERROR: " + t;
        }
        notSupported.add(o);

        o = new NotSupportedObs("AppCacheEnabled");
        o.setValue = Boolean.TRUE;
        o.getValue = "setter:" + optionalCall("setAppCacheEnabled",
                new Class<?>[]{boolean.class}, new Object[]{Boolean.TRUE});
        notSupported.add(o);

        o = new NotSupportedObs("AppCachePath");
        String path = getFilesDir().getAbsolutePath() + "/appcache";
        o.setValue = path;
        o.getValue = "setter:" + optionalCall("setAppCachePath",
                new Class<?>[]{String.class}, new Object[]{path})
                + ",getter:" + optionalCall("getAppCachePath",
                new Class<?>[]{}, new Object[]{});
        notSupported.add(o);

        o = new NotSupportedObs("AppCacheMaxSize");
        o.setValue = Long.valueOf(1048576L);
        o.getValue = "setter:" + optionalCall("setAppCacheMaxSize",
                new Class<?>[]{long.class}, new Object[]{Long.valueOf(1048576L)});
        notSupported.add(o);
    }

    /** Reflectively call a possibly-removed WebSettings method; report honestly. */
    private String optionalCall(String method, Class<?>[] types, Object[] args) {
        try {
            java.lang.reflect.Method m = WebSettings.class.getMethod(method, types);
            m.invoke(webSettings, args);
            return "ok";
        } catch (java.lang.reflect.InvocationTargetException e) {
            return "threw:" + e.getCause();
        } catch (Throwable t) {
            return "absent:" + t.getClass().getSimpleName();
        }
    }

    private String readAsset(String name) throws Exception {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    private void finishAll() {
        try {
            emit(buildJson());
        } catch (Throwable t) {
            Log.e(TAG, "build failed", t);
            emitStartupError(t);
        }
    }

    private String backendState() {
        for (ScenarioObs o : scenarios) {
            if (o.pageLoaded) {
                return "present";
            }
        }
        String vm = null;
        try {
            vm = System.getProperty("java.vm.name");
        } catch (Throwable ignored) {
        }
        String gate = getGateValue();
        if (vm != null && vm.toLowerCase().indexOf("dalvik") >= 0) {
            return "android-load-failed";
        }
        if (gate == null || gate.length() == 0) {
            return "gated";
        }
        return "atlexpected-but-absent";
    }

    private static String getGateValue() {
        try {
            return System.getenv(GATE_VAR);
        } catch (Throwable t) {
            return null;
        }
    }

    private String buildJson() {
        String apkSha = "";
        try {
            apkSha = sha256Hex(new File(getPackageCodePath()));
        } catch (Throwable ignored) {
        }
        String vm = null;
        try {
            vm = System.getProperty("java.vm.name");
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"status\":\"OK\",\"exceptions\":[]");
        sb.append(",\"config\":{\"targetSdkVersion\":37,\"minSdkVersion\":24");
        sb.append(",\"baseUrl\":").append(jsonString(BASE_URL));
        sb.append(",\"assetPage\":").append(jsonString(ASSET_PAGE));
        sb.append(",\"implementedSettings\":[").append(jsonStringArray(IMPLEMENTED)).append(']');
        sb.append(",\"notSupportedSettings\":[").append(jsonStringArray(NOT_SUPPORTED)).append(']');
        sb.append(",\"scenarios\":[{\"id\":\"A\",\"needInitialFocus\":true}");
        sb.append(",{\"id\":\"B\",\"needInitialFocus\":false}]}");
        sb.append(",\"completion\":{\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END));
        sb.append(",\"doneMarker\":").append(jsonString(DONE_MARKER)).append('}');
        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');
        sb.append(",\"engine\":{\"vmName\":").append(jsonString(vm));
        sb.append(",\"gatingEnvVar\":").append(jsonString(GATE_VAR));
        sb.append(",\"gatingEnvValue\":").append(jsonString(getGateValue()));
        sb.append(",\"webviewBackend\":").append(jsonString(backendState()));
        sb.append(",\"observable\":").append(ua.observable);
        sb.append(",\"error\":").append(jsonString(ua.error));
        sb.append(",\"getDefaultUserAgent\":").append(jsonString(ua.getDefaultUserAgent));
        sb.append(",\"userAgentDefault\":").append(jsonString(ua.userAgentDefault));
        sb.append(",\"userAgentAfterSet\":").append(jsonString(ua.userAgentAfterSet));
        sb.append(",\"userAgentRoundTrip\":").append(ua.userAgentRoundTrip).append('}');
        sb.append(",\"settings\":[");
        for (int i = 0; i < settings.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(settingJson(settings.get(i)));
        }
        sb.append(']');
        sb.append(",\"notSupported\":[");
        for (int i = 0; i < notSupported.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(notSupportedJson(notSupported.get(i)));
        }
        sb.append(']');
        sb.append(",\"scenarios\":[");
        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(scenarioJson(scenarios.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String settingJson(SettingObs o) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(jsonString(o.name));
        sb.append(",\"supported\":true,\"observable\":").append(o.observable);
        sb.append(",\"error\":").append(jsonString(o.error));
        sb.append(",\"defaultValue\":").append(boolOrNull(o.observable ? Boolean.valueOf(o.defaultValue) : null));
        sb.append(",\"setTrueGet\":").append(boolOrNull(o.observable ? Boolean.valueOf(o.setTrueGet) : null));
        sb.append(",\"setFalseGet\":").append(boolOrNull(o.observable ? Boolean.valueOf(o.setFalseGet) : null));
        sb.append(",\"roundTrip\":").append(boolOrNull(o.observable ? Boolean.valueOf(o.roundTrip) : null));
        sb.append('}');
        return sb.toString();
    }

    private static String notSupportedJson(NotSupportedObs o) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(jsonString(o.name));
        sb.append(",\"setValue\":").append(jsonValue(o.setValue));
        sb.append(",\"getValue\":").append(jsonValue(o.getValue));
        sb.append(",\"note\":\"NOT_SUPPORTED\"}");
        return sb.toString();
    }

    private static String scenarioJson(ScenarioObs o) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(jsonString(o.id));
        sb.append(",\"needInitialFocusSet\":").append(o.needInitialFocusSet);
        sb.append(",\"pageLoaded\":").append(o.pageLoaded);
        sb.append(",\"jsObserved\":").append(o.jsObserved);
        sb.append(",\"observedOnMainThread\":").append(o.observedOnMainThread);
        sb.append(",\"activeId\":").append(jsonString(o.activeId));
        sb.append(",\"activeTag\":").append(jsonString(o.activeTag));
        sb.append(",\"documentHasFocus\":").append(boolOrNull(o.documentHasFocus));
        sb.append(",\"firstFieldEditable\":").append(boolOrNull(o.firstFieldEditable));
        sb.append(",\"secondFieldEditable\":").append(boolOrNull(o.secondFieldEditable));
        sb.append(",\"field1TabIndex\":").append(o.field1TabIndex == null ? "null" : String.valueOf(o.field1TabIndex));
        sb.append(",\"webViewFocused\":").append(o.webViewFocused);
        sb.append(",\"imeRequested\":").append(boolOrNull(o.imeRequested));
        sb.append(",\"observationSource\":").append(jsonString(o.observationSource));
        sb.append(",\"error\":").append(jsonString(o.error));
        sb.append(",\"jsError\":").append(jsonString(o.jsError));
        sb.append('}');
        return sb.toString();
    }

    private static String boolOrNull(Boolean b) {
        return b == null ? "null" : b.toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            return v.toString();
        }
        return jsonString(String.valueOf(v));
    }

    private static String jsonStringArray(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(a[i]));
        }
        return sb.toString();
    }

    private void writeResults(String json) {
        writeJson(new File(getFilesDir(), "results.json"), json);
        File external = getExternalFilesDir(null);
        if (external != null) {
            writeJson(new File(external, "results.json"), json);
        }
    }

    private void writeJson(File file, String json) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(json.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "failed to write " + file, e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            if (in != null) {
                in.close();
            }
        }
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
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

    static final class SettingObs {
        final String name;
        boolean observable;
        String error;
        boolean defaultValue;
        boolean setTrueGet;
        boolean setFalseGet;
        boolean roundTrip;
        SettingObs(String name) { this.name = name; }
    }

    static final class UaObs {
        boolean observable;
        String error;
        String getDefaultUserAgent;
        String userAgentDefault;
        String userAgentAfterSet;
        boolean userAgentRoundTrip;
    }

    static final class NotSupportedObs {
        final String name;
        Object setValue;
        Object getValue;
        NotSupportedObs(String name) { this.name = name; }
    }

    static final class ScenarioObs {
        String id;
        boolean needInitialFocusSet;
        boolean pageLoaded;
        boolean jsObserved;
        boolean observedOnMainThread;
        String activeId;
        String activeTag;
        Boolean documentHasFocus;
        Boolean firstFieldEditable;
        Boolean secondFieldEditable;
        Integer field1TabIndex;
        boolean webViewFocused;
        Boolean imeRequested;
        String observationSource;
        String error;
        String jsError;
    }

    abstract static class BoolSetting {
        final String name;
        BoolSetting(String name) { this.name = name; }
        abstract boolean get();
        abstract void set(boolean v);
    }
}
