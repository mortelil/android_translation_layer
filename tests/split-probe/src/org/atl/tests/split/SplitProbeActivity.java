/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.split;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APP-COMPAT case F probe: controlled split / APKM family.
 *
 * The probe is deliberately minimal (Activity + java.util.zip + AssetManager /
 * PackageManager). It observes, per run and deterministically:
 *
 *   - which APK members the runtime actually selected (ATL: atl.app.class.path
 *     after split resolution; Android: ApplicationInfo.sourceDir +
 *     splitSourceDirs) and their member order (base must be first);
 *   - per member: dex presence, native ABI dirs, top-level assets;
 *   - whether any NON-base member carries dex (must never be dex-compiled);
 *   - that the base-dex helper class was loaded (static-init marker);
 *   - that libsplitprobe.so loaded and from which split / ABI it was provided;
 *   - the extracted native path;
 *   - whether the density / language split sentinels resolve;
 *   - the probe exit status (bitmask of failures).
 *
 * Completion is the sentinel-wrapped JSON (SPLITPROBE_JSON_BEGIN/_END) plus the
 * SPLITPROBE_DONE marker on stdout and files/results.json; the process then
 * exits with the computed status (never used as the only completion signal).
 */
public class SplitProbeActivity extends Activity {
    private static final String TAG = "SPLITPROBE";
    private static final String TASK_ID = "APP-COMPAT-F-SPLIT";
    private static final String SENTINEL_BEGIN = "SPLITPROBE_JSON_BEGIN";
    private static final String SENTINEL_END = "SPLITPROBE_JSON_END";
    private static final String DONE_MARKER = "SPLITPROBE_DONE";

    private static final String NATIVE_LIB_NAME = "libsplitprobe.so";
    private static final int NATIVE_VALUE_EXPECTED = 0x5A17; // 23063

    private static final String DENSITY_SENTINEL = "xxhdpi_sentinel.txt";
    private static final String LANGUAGE_SENTINEL = "lang_sentinel.txt";

    private static final String[] EXPECTED_SPLITS = {
            "config.x86_64", "config.xxhdpi", "config.en"};

    private final String runId = "split-probe-" + Long.toHexString(System.currentTimeMillis())
            + "-" + Integer.toHexString((int) (Math.random() * 0xffff));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json;
        int status;
        try {
            Result r = runProbe();
            json = r.json;
            status = r.exitStatus;
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            status = 1;
            json = "{\"task_id\":" + jsonString(TASK_ID)
                    + ",\"run_id\":" + jsonString(runId)
                    + ",\"status\":\"ERROR\""
                    + ",\"exceptions\":[" + jsonString(t.getClass().getName() + ": " + t.getMessage()) + "]"
                    + ",\"exit_status\":1}";
        }
        emit(json);
        // Deterministic completion without killing the process (ATL GUI apps
        // do not self-exit): the exit status is the explicit status line; the
        // completion signal is SPLITPROBE_DONE / results.json.
        System.out.println("SPLITPROBE_EXIT_STATUS=" + status);
        System.out.flush();
    }

    private static final class Result {
        String json;
        int exitStatus;
    }

    private Result runProbe() throws Exception {
        int status = 0;

        // 1. Which members did the runtime select?
        List<String> members = new ArrayList<String>();
        String memberSource;
        String classPathProp = System.getProperty("atl.app.resolved.class.path", System.getProperty("atl.app.class.path"));
        if (classPathProp != null && classPathProp.length() > 0) {
            memberSource = "atl.app.class.path";
            for (String p : classPathProp.split(":")) {
                if (p.length() > 0) {
                    members.add(p);
                }
            }
        } else {
            memberSource = "application_info";
            ApplicationInfo ai = getApplicationInfo();
            if (ai.sourceDir != null) {
                members.add(ai.sourceDir);
            }
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) {
                    if (s != null && s.length() > 0) {
                        members.add(s);
                    }
                }
            }
        }

        boolean containerUnpacked = false;
        List<MemberObs> memberObs = new ArrayList<MemberObs>();
        for (String path : members) {
            MemberObs m = scanMember(path);
            memberObs.add(m);
            if (path.contains("/atl-splits-")) {
                containerUnpacked = true;
            }
        }

        // Base must be first: the runtime keys the primary manifest/classes on it.
        boolean baseFirst = !memberObs.isEmpty() && memberObs.get(0).hasDex;

        // No resource/native-only split may carry dex.
        boolean dexFromNonBase = false;
        String nativeFromSplit = null;
        String nativeAbi = null;
        for (MemberObs m : memberObs) {
            if (!m.isBase && m.hasDex) {
                dexFromNonBase = true;
            }
            if (m.nativeAbis != null && m.nativeAbis.containsKey(NATIVE_LIB_NAME)) {
                if (nativeFromSplit == null) {
                    nativeFromSplit = m.file;
                    nativeAbi = m.nativeAbis.get(NATIVE_LIB_NAME);
                }
            }
        }

        // 2. Base dex class load is observable via the static initializer.
        String classInitMarker = ProbeLoader.STATIC_INIT_MARKER;
        boolean classLoaded = ProbeLoader.isInitialized();

        // 3. Native library from the ABI-matching split.
        String nativeLoadError = ProbeLoader.loadLibrary();
        boolean nativeLoadOk = nativeLoadError == null;
        int nativeValue = -1;
        if (nativeLoadOk) {
            nativeValue = ProbeLoader.splitprobeValue();
        }
        String nativeLibPath = findExtractedNativeLib();
        boolean onMainThread = Looper.myLooper() == Looper.getMainLooper();

        // 4. Split sentinels (assets merged across the selected members).
        String densitySentinel = readAssetString(DENSITY_SENTINEL);
        String languageSentinel = readAssetString(LANGUAGE_SENTINEL);
        boolean densityResolved = densitySentinel != null;
        boolean languageResolved = languageSentinel != null;

        // Informational: res/raw + string resources from the splits, resolved
        // through the (merged) resource table. Not part of the pass gate.
        String densityRawInfo = readRawString("density_raw");
        String languageStringInfo = readNamedString("split_lang");

        if (!baseFirst) {
            status |= 1;
        }
        if (dexFromNonBase) {
            status |= 2;
        }
        if (!classLoaded) {
            status |= 4;
        }
        if (!nativeLoadOk) {
            status |= 8;
        }
        if (nativeLoadOk && nativeValue != NATIVE_VALUE_EXPECTED) {
            status |= 16;
        }
        if (!densityResolved) {
            status |= 32;
        }
        if (!languageResolved) {
            status |= 64;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"status\":").append(jsonString(status == 0 ? "OK" : "DEGRADED"));
        sb.append(",\"config\":{");
        sb.append("\"expected_splits\":").append(jsonStringArray(EXPECTED_SPLITS));
        sb.append(",\"native_lib\":").append(jsonString(NATIVE_LIB_NAME));
        sb.append(",\"native_value_expected\":").append(NATIVE_VALUE_EXPECTED);
        sb.append(",\"density_sentinel\":").append(jsonString(DENSITY_SENTINEL));
        sb.append(",\"language_sentinel\":").append(jsonString(LANGUAGE_SENTINEL));
        sb.append('}');
        sb.append(",\"observations\":{");
        sb.append("\"member_source\":").append(jsonString(memberSource));
        sb.append(",\"selected_members\":").append(jsonStringArray(
                members.toArray(new String[0])));
        sb.append(",\"members\":[");
        for (int i = 0; i < memberObs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(memberObs.get(i).json());
        }
        sb.append(']');
        sb.append(",\"member_order\":").append(jsonStringArray(memberFileNames(memberObs)));
        sb.append(",\"container_unpacked\":").append(containerUnpacked);
        sb.append(",\"base_first\":").append(baseFirst);
        sb.append(",\"dex_from_non_base_splits\":").append(dexFromNonBase);
        sb.append(",\"class_loaded\":").append(classLoaded);
        sb.append(",\"class_init_marker\":").append(jsonString(classInitMarker));
        sb.append(",\"native_load_ok\":").append(nativeLoadOk);
        sb.append(",\"native_load_error\":").append(jsonString(nativeLoadError));
        sb.append(",\"native_lib_path\":").append(jsonString(nativeLibPath));
        sb.append(",\"native_from_split\":").append(jsonString(nativeFromSplit));
        sb.append(",\"native_abi\":").append(jsonString(nativeAbi));
        sb.append(",\"native_value\":").append(nativeValue);
        sb.append(",\"density_sentinel_value\":").append(jsonString(densitySentinel));
        sb.append(",\"density_sentinel_resolved\":").append(densityResolved);
        sb.append(",\"language_sentinel_value\":").append(jsonString(languageSentinel));
        sb.append(",\"language_sentinel_resolved\":").append(languageResolved);
        sb.append(",\"density_raw_value\":").append(jsonString(densityRawInfo));
        sb.append(",\"language_string_value\":").append(jsonString(languageStringInfo));
        sb.append(",\"on_main_thread\":").append(onMainThread);
        sb.append(",\"exceptions\":[]");
        sb.append('}');
        sb.append(",\"exit_status\":").append(status);
        sb.append(",\"completed_marker\":").append(jsonString(DONE_MARKER));
        sb.append('}');

        Result r = new Result();
        r.json = sb.toString();
        r.exitStatus = status;
        return r;
    }

    // ---------------------------------------------------------------- members

    private MemberObs scanMember(String path) {
        MemberObs m = new MemberObs();
        m.path = path;
        m.file = new File(path).getName();
        m.nativeAbis = new LinkedHashMap<String, String>();
        m.assets = new TreeSet<String>();
        ZipFile zip = null;
        try {
            zip = new ZipFile(path);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.matches("^classes\\d*\\.dex$")) {
                    m.hasDex = true;
                } else if (name.startsWith("lib/")) {
                    // lib/<abi>/<name>
                    int slash = name.indexOf('/', 4);
                    if (slash > 4) {
                        String abi = name.substring(4, slash);
                        String base = name.substring(slash + 1);
                        if (NATIVE_LIB_NAME.equals(base)) {
                            m.nativeAbis.put(NATIVE_LIB_NAME, abi);
                        } else {
                            m.nativeAbis.put(base, abi);
                        }
                    }
                } else if (!name.startsWith("META-INF/")
                        && !name.startsWith("res/")
                        && (name.indexOf('/') < 0)
                        && !"AndroidManifest.xml".equals(name)
                        && !"resources.arsc".equals(name)) {
                    if (!e.isDirectory()) {
                        m.assets.add(name);
                    }
                }
            }
        } catch (Throwable t) {
            m.scanError = t.getClass().getName() + ": " + t.getMessage();
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
        m.isBase = m.hasDex;
        return m;
    }

    private static String[] memberFileNames(List<MemberObs> ms) {
        String[] out = new String[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            out[i] = ms.get(i).file;
        }
        return out;
    }

    static final class MemberObs {
        String path;
        String file;
        boolean hasDex;
        boolean isBase;
        String scanError;
        Map<String, String> nativeAbis; // entry name -> abi (NATIVE_LIB_NAME -> abi)
        TreeSet<String> assets;

        String json() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"file\":").append(jsonString(file));
            sb.append(",\"path\":").append(jsonString(path));
            sb.append(",\"has_dex\":").append(hasDex);
            sb.append(",\"is_base\":").append(isBase);
            sb.append(",\"native_abis\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : nativeAbis.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
            }
            sb.append('}');
            sb.append(",\"assets\":[");
            boolean firstA = true;
            for (String a : assets) {
                if (!firstA) {
                    sb.append(',');
                }
                firstA = false;
                sb.append(jsonString(a));
            }
            sb.append(']');
            sb.append(",\"scan_error\":").append(jsonString(scanError));
            sb.append('}');
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- native

    /** Recursively looks for the extracted libsplitprobe.so under the app data dir. */
    private String findExtractedNativeLib() {
        List<String> found = new ArrayList<String>();
        try {
            File root = getFilesDir().getParentFile();
            collectLibs(root, 0, found);
        } catch (Throwable ignored) {
        }
        try {
            String nd = getApplicationInfo().nativeLibraryDir;
            if (nd != null) {
                File f = new File(nd, NATIVE_LIB_NAME);
                if (f.isFile()) {
                    found.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {
        }
        if (found.isEmpty()) {
            return null;
        }
        Collections.sort(found);
        return found.get(0);
    }

    private void collectLibs(File dir, int depth, List<String> out) {
        if (dir == null || depth > 6) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                collectLibs(k, depth + 1, out);
            } else if (NATIVE_LIB_NAME.equals(k.getName())) {
                out.add(k.getAbsolutePath());
            }
        }
    }

    // -------------------------------------------------------------- assets/res

    private String readAssetString(String name) {
        InputStream in = null;
        try {
            AssetManager am = getAssets();
            in = am.open(name);
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private String readRawString(String resName) {
        InputStream in = null;
        try {
            int id = getResources().getIdentifier(resName, "raw", getPackageName());
            if (id == 0) {
                return null;
            }
            in = getResources().openRawResource(id);
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private String readNamedString(String resName) {
        try {
            int id = getResources().getIdentifier(resName, "string", getPackageName());
            if (id == 0) {
                return null;
            }
            return getResources().getString(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8").trim();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    // -------------------------------------------------------------- emission

    private void emit(String json) {
        System.out.println(SENTINEL_BEGIN);
        System.out.println(json);
        System.out.println(SENTINEL_END);
        System.out.flush();
        writeResults(json);
        System.out.println(DONE_MARKER);
        System.out.flush();
    }

    private void writeResults(String json) {
        writeJson(new File(getFilesDir(), "results.json"), json);
        File external = getExternalFilesDir(null);
        if (external != null) {
            writeJson(new File(external, "results.json"), json);
        }
    }

    private static void writeJson(File file, String json) {
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

    private static String jsonStringArray(String[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(a[i]));
        }
        return sb.append(']').toString();
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
        return sb.append('"').toString();
    }
}
