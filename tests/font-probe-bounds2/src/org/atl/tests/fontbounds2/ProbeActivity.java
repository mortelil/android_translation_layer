/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.fontbounds2;

import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Rect;
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
 * FONT-005 DECISION probe for the Paint.getTextBounds (ink box) repair.
 *
 * The frozen FONT-004 probe measures only the four ink-box ints. The
 * impl-font-bounds report (agent-work/REVIEW-REPAIRS/impl-font-bounds/report.md)
 * showed that the residual mismatches are concentrated in the per-glyph pen /
 * advance law, which cannot be distinguished from FONT-004 output alone. This
 * probe therefore records, on the SAME bundled DejaVuSans.ttf (same SHA-256),
 * the SAME fractional sizes (16.3f, 33.4f, 64.0f) and alignment LEFT:
 *
 *   1. Paint.getTextWidths(String, float[]) advances for the discriminating
 *      strings, each as Float.toHexString plus decimal, and their float sum
 *      (integer advances => hinted, fractional => linear).
 *   2. Paint.getTextBounds of SINGLE glyphs (H e l o j ... space empty U+1D54F)
 *      to isolate per-glyph ink boxes.
 *   3. Paint.getFontMetricsInt() (all five fields) and getFontMetrics() floats.
 *   4. Paint.getTextBounds("Hello",1,4) next to getTextBounds("ell",0,3) and
 *      getTextBounds("Hello",0,5) to compare the range vs substring path.
 *   5. Paint.measureText for the same strings (hex + decimal).
 *
 * Emits one sentinel-wrapped JSON object (FONT005_JSON_BEGIN/_END) on stdout,
 * persists the same JSON to filesDir/results.json, then prints FONT005_DONE.
 * Completion is the sentinel / results.json, never process exit (runbook §8).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "FONT005";
    private static final String TASK_ID = "FONT-005";
    private static final String CASE_ID = "FONT-BOUNDS-DECISION";
    private static final String FONT_ASSET = "DejaVuSans.ttf";
    private static final String EXPECTED_SHA256 =
            "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954";
    private static final float[] TEXT_SIZES = {16.3f, 33.4f, 64.0f};
    private static final String SENTINEL_BEGIN = "FONT005_JSON_BEGIN";
    private static final String SENTINEL_END = "FONT005_JSON_END";
    private static final String DONE_MARKER = "FONT005_DONE";

    private static final String[] WIDTH_STRINGS = {
            "Hello", "jgy", "AV", "\u2026", "e\u0301",
            "\uD835\uDD4F", " ", ""
    };
    private static final String[] WIDTH_NAMES = {
            "Hello", "jgy", "AV", "ellipsis", "combining_e_acute",
            "nonbmp_u1d54f", "space", "empty"
    };
    private static final String[] GLYPHS = {
            "H", "e", "l", "o", "j", "\u2026", " ", "", "\uD835\uDD4F"
    };
    private static final String[] GLYPH_NAMES = {
            "H", "e", "l", "o", "j", "ellipsis", "space", "empty", "nonbmp_u1d54f"
    };

    private final String runId =
            "font-005-apk-" + Long.toHexString(System.currentTimeMillis());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json;
        try {
            json = runProbe();
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            json = "{\"task_id\":" + jsonString(TASK_ID)
                    + ",\"case_id\":" + jsonString(CASE_ID)
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
        Typeface typeface = Typeface.createFromFile(fontFile);

        StringBuilder exceptions = new StringBuilder();
        if (!EXPECTED_SHA256.equals(fontSha)) {
            appendException(exceptions, "font sha mismatch: expected "
                    + EXPECTED_SHA256 + " got " + fontSha);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"case_id\":").append(jsonString(CASE_ID));
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
            sb.append(floatJson(TEXT_SIZES[i]));
        }
        sb.append("],\"alignment\":\"LEFT\"}");
        sb.append(",\"completion\":{\"marker\":").append(jsonString(DONE_MARKER));
        sb.append(",\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END)).append('}');
        sb.append(",\"font\":{\"sha256\":").append(jsonString(fontSha));
        sb.append(",\"asset\":").append(jsonString(FONT_ASSET)).append('}');
        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');

        sb.append(",\"text_widths\":[");
        boolean first = true;
        for (float size : TEXT_SIZES) {
            Paint paint = newPaint(typeface, size);
            for (int i = 0; i < WIDTH_STRINGS.length; i++) {
                String text = WIDTH_STRINGS[i];
                float[] widths = new float[Math.max(1, text.length())];
                int count = paint.getTextWidths(text, widths);
                float sum = 0.0f;
                for (int k = 0; k < count; k++) {
                    sum += widths[k];
                }
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append('{');
                sb.append("\"size\":").append(floatJson(size));
                sb.append(",\"name\":").append(jsonString(WIDTH_NAMES[i]));
                sb.append(",\"text\":").append(jsonString(text));
                sb.append(",\"utf16\":").append(utf16Json(text));
                sb.append(",\"count\":").append(count);
                sb.append(",\"widths\":[");
                for (int k = 0; k < count; k++) {
                    if (k > 0) {
                        sb.append(',');
                    }
                    sb.append(floatPairJson(widths[k]));
                }
                sb.append(']');
                sb.append(",\"sum\":").append(floatPairJson(sum));
                sb.append('}');
            }
        }
        sb.append(']');

        sb.append(",\"glyph_bounds\":[");
        first = true;
        for (float size : TEXT_SIZES) {
            Paint paint = newPaint(typeface, size);
            for (int i = 0; i < GLYPHS.length; i++) {
                String text = GLYPHS[i];
                Rect rect = new Rect();
                paint.getTextBounds(text, 0, text.length(), rect);
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append('{');
                sb.append("\"size\":").append(floatJson(size));
                sb.append(",\"name\":").append(jsonString(GLYPH_NAMES[i]));
                sb.append(",\"text\":").append(jsonString(text));
                sb.append(",\"utf16\":").append(utf16Json(text));
                sb.append(",\"rect\":").append(rectJson(rect));
                sb.append('}');
            }
        }
        sb.append(']');

        sb.append(",\"font_metrics\":[");
        first = true;
        for (float size : TEXT_SIZES) {
            Paint paint = newPaint(typeface, size);
            Paint.FontMetricsInt mi = paint.getFontMetricsInt();
            Paint.FontMetrics mf = paint.getFontMetrics();
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append('{');
            sb.append("\"size\":").append(floatJson(size));
            sb.append(",\"int\":{");
            sb.append("\"top\":").append(mi.top);
            sb.append(",\"ascent\":").append(mi.ascent);
            sb.append(",\"descent\":").append(mi.descent);
            sb.append(",\"bottom\":").append(mi.bottom);
            sb.append(",\"leading\":").append(mi.leading);
            sb.append('}');
            sb.append(",\"float\":{");
            sb.append("\"top\":").append(floatPairJson(mf.top));
            sb.append(",\"ascent\":").append(floatPairJson(mf.ascent));
            sb.append(",\"descent\":").append(floatPairJson(mf.descent));
            sb.append(",\"bottom\":").append(floatPairJson(mf.bottom));
            sb.append(",\"leading\":").append(floatPairJson(mf.leading));
            sb.append('}');
            sb.append('}');
        }
        sb.append(']');

        sb.append(",\"range_bounds\":[");
        first = true;
        for (float size : TEXT_SIZES) {
            Paint paint = newPaint(typeface, size);
            first = appendRange(sb, first, paint, size, "Hello_range_1_4", "Hello", 1, 4);
            first = appendRange(sb, first, paint, size, "ell_substring_0_3", "ell", 0, 3);
            first = appendRange(sb, first, paint, size, "Hello_full_0_5", "Hello", 0, 5);
        }
        sb.append(']');

        sb.append(",\"measure_text\":[");
        first = true;
        for (float size : TEXT_SIZES) {
            Paint paint = newPaint(typeface, size);
            for (int i = 0; i < WIDTH_STRINGS.length; i++) {
                String text = WIDTH_STRINGS[i];
                float w = paint.measureText(text);
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append('{');
                sb.append("\"size\":").append(floatJson(size));
                sb.append(",\"name\":").append(jsonString(WIDTH_NAMES[i]));
                sb.append(",\"text\":").append(jsonString(text));
                sb.append(",\"utf16\":").append(utf16Json(text));
                sb.append(",\"measureText\":").append(floatPairJson(w));
                sb.append('}');
            }
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    private static Paint newPaint(Typeface typeface, float size) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        paint.setTextAlign(Paint.Align.LEFT);
        return paint;
    }

    private static boolean appendRange(StringBuilder sb, boolean first, Paint paint,
                                       float size, String name, String text,
                                       int start, int end) {
        Rect rect = new Rect();
        paint.getTextBounds(text, start, end, rect);
        if (!first) {
            sb.append(",\n");
        }
        sb.append('{');
        sb.append("\"size\":").append(floatJson(size));
        sb.append(",\"name\":").append(jsonString(name));
        sb.append(",\"text\":").append(jsonString(text));
        sb.append(",\"start\":").append(start);
        sb.append(",\"end\":").append(end);
        sb.append(",\"rect\":").append(rectJson(rect));
        sb.append('}');
        return false;
    }

    private static String rectJson(Rect r) {
        return "{\"left\":" + r.left + ",\"top\":" + r.top
                + ",\"right\":" + r.right + ",\"bottom\":" + r.bottom + "}";
    }

    private static String utf16Json(String s) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append((int) s.charAt(i));
        }
        return sb.append(']').toString();
    }

    /** {"hex":"0x1.8p2","dec":6.0} — Float.toHexString plus decimal. */
    private static String floatPairJson(float v) {
        return "{\"hex\":" + jsonString(Float.toHexString(v))
                + ",\"dec\":" + floatJson(v) + "}";
    }

    private static String floatJson(float v) {
        if (Float.isNaN(v)) {
            return jsonString("NaN");
        }
        if (Float.isInfinite(v)) {
            return jsonString(v > 0 ? "Infinity" : "-Infinity");
        }
        return Float.toString(v);
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

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
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
        sb.append('"');
        return sb.toString();
    }
}
