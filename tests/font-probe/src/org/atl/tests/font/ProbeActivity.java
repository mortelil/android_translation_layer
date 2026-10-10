/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.font;

import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * FONT-003 probe: Paint.getFontMetrics() (float) and getFontMetricsInt() (int)
 * for one identical bundled font file (DejaVuSans.ttf) across the fractional
 * text sizes and the two style variants fixed by spec-FONT-METRICS.md.
 *
 * Emits a single sentinel-wrapped JSON object (FONT003_JSON_BEGIN/_END) on
 * stdout, persists the same JSON to files/results.json, and finally prints the
 * completion marker FONT003_DONE. The completion signal is the marker /
 * results.json, never process exit (see runbook section 8).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "FONT003";
    private static final String TASK_ID = "FONT-003";
    private static final String FONT_ASSET = "DejaVuSans.ttf";
    private static final String EXPECTED_SHA256 =
            "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954";
    private static final float[] TEXT_SIZES = {
            12.3f, 15.7f, 33.4f, 47.9f, 64.0f, 100.91f
    };
    private static final String[] STYLE_NAMES = {"normal", "bold_italic"};
    private static final int[] STYLE_VALUES = {
            Typeface.NORMAL,
            Typeface.BOLD | Typeface.ITALIC
    };
    private static final String SENTINEL_BEGIN = "FONT003_JSON_BEGIN";
    private static final String SENTINEL_END = "FONT003_JSON_END";
    private static final String DONE_MARKER = "FONT003_DONE";

    private final String runId =
            "font-003-apk-" + Long.toHexString(System.currentTimeMillis());

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
        File fontFile = new File(getFilesDir(), FONT_ASSET);
        copyAssetToPrivate(FONT_ASSET, fontFile);
        String fontSha = sha256Hex(fontFile);
        String apkSha = sha256Hex(new File(getPackageCodePath()));

        Typeface base = Typeface.createFromFile(fontFile);
        Typeface[] styles = new Typeface[STYLE_NAMES.length];
        for (int i = 0; i < styles.length; i++) {
            styles[i] = (STYLE_VALUES[i] == Typeface.NORMAL)
                    ? base
                    : Typeface.create(base, STYLE_VALUES[i]);
        }

        StringBuilder exceptions = new StringBuilder();
        if (!EXPECTED_SHA256.equals(fontSha)) {
            appendException(exceptions, "font sha mismatch: expected "
                    + EXPECTED_SHA256 + " got " + fontSha);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"status\":\"").append(exceptions.length() == 0 ? "OK" : "ERROR");
        sb.append("\",\"exceptions\":[").append(exceptions).append(']');
        sb.append(",\"config\":{\"antiAlias\":true,\"fontAsset\":");
        sb.append(jsonString(FONT_ASSET));
        sb.append(",\"expectedSha256\":").append(jsonString(EXPECTED_SHA256));
        sb.append(",\"textSizes\":[");
        for (int i = 0; i < TEXT_SIZES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(doubleJson(TEXT_SIZES[i]));
        }
        sb.append("],\"styles\":[");
        for (int i = 0; i < STYLE_NAMES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(STYLE_NAMES[i]));
        }
        sb.append("]}");
        sb.append(",\"completion\":{\"marker\":").append(jsonString(DONE_MARKER));
        sb.append(",\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END)).append('}');
        sb.append(",\"font\":{\"sha256\":").append(jsonString(fontSha));
        sb.append(",\"asset\":").append(jsonString(FONT_ASSET)).append('}');
        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');
        sb.append(",\"metrics\":[");
        boolean first = true;
        for (float size : TEXT_SIZES) {
            for (int s = 0; s < styles.length; s++) {
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setTextSize(size);
                paint.setTypeface(styles[s]);
                Paint.FontMetrics fm = paint.getFontMetrics();
                Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('{');
                sb.append("\"size\":").append(doubleJson(size));
                sb.append(",\"style\":").append(jsonString(STYLE_NAMES[s]));
                sb.append(",\"fm\":{\"ascent\":").append(doubleJson(fm.ascent));
                sb.append(",\"descent\":").append(doubleJson(fm.descent));
                sb.append(",\"top\":").append(doubleJson(fm.top));
                sb.append(",\"bottom\":").append(doubleJson(fm.bottom));
                sb.append(",\"leading\":").append(doubleJson(fm.leading)).append('}');
                sb.append(",\"fmi\":{\"top\":").append(fmi.top);
                sb.append(",\"ascent\":").append(fmi.ascent);
                sb.append(",\"descent\":").append(fmi.descent);
                sb.append(",\"bottom\":").append(fmi.bottom);
                sb.append(",\"leading\":").append(fmi.leading).append('}');
                sb.append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendException(StringBuilder sb, String message) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(jsonString(message));
    }

    private void copyAssetToPrivate(String assetName, File dest) throws Exception {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getAssets().open(assetName);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
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

    private static String doubleJson(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v) + ".0";
        }
        return Double.toString(v);
    }

    private static String jsonString(String s) {
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
}
