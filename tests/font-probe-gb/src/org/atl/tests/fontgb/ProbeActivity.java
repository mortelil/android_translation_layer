/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.fontgb;

import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * FONT-007 (milestone G-B) probe: variable font axes, multi-run fallback and a
 * bitmap (PCF) face.
 *
 * Records, on the SAME bundled font bytes that both the Android reference and
 * ATL load (each copied to private storage and hashed with SHA-256), the three
 * case families of the frozen spec:
 *
 *   V (variable axes): the typeface is loaded from the variable file
 *      (Ubuntu[wdth,wght].ttf, bundled as Ubuntu-Variable.ttf). For the axis
 *      settings unset default / "'wght' 100" / "'wght' 700" / "'wdth' 75" and
 *      sizes {16.3, 33.4} and strings {"Hello","AV","m"} we apply the axis
 *      twice: via Paint.setFontVariationSettings (returns applied=boolean) and
 *      via Typeface.Builder.setFontVariationSettings. Per cell we record the
 *      per-glyph getTextWidths, measureText, getTextBounds, the resolved
 *      typeface echo (getWeight(), isItalic()) and the resolution class:
 *         "value"        setting produced values (or the default cell)
 *         "axis_ignored" the API reported the setting was NOT applied
 *         "exception"    the call threw (recorded with class+message)
 *   F (multi-run fallback): a controlled chain built with
 *      Typeface.CustomFallbackBuilder (base family = DejaVu, custom fallback
 *      family = Noto CJK) measuring "A\u4E16B" so glyph 2 is served by the
 *      FALLBACK face.
 *   B (bitmap face): Typeface.createFromFile(8x13.pcf); records whether loading
 *      throws and, if it loads, the metrics for "Hello".
 *
 * Each cell is written inside a try/catch so one failing case never aborts the
 * rest; a cell that throws records the exception class+message in that cell.
 *
 * Emits one sentinel-wrapped JSON object (FONT007_JSON_BEGIN/_END) on stdout,
 * persists the same JSON to filesDir/results.json, then prints FONT007_DONE.
 * Completion is the sentinel / results.json, never process exit (runbook §8).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "FONT007";
    private static final String TASK_ID = "FONT-007";
    private static final String CASE_ID = "FONT-GLYPH-GB";
    private static final String SENTINEL_BEGIN = "FONT007_JSON_BEGIN";
    private static final String SENTINEL_END = "FONT007_JSON_END";
    private static final String DONE_MARKER = "FONT007_DONE";

    private static final String ASSET_DEJAVU = "DejaVuSans.ttf";
    private static final String ASSET_UBUNTU = "Ubuntu-Variable.ttf";
    private static final String ASSET_NOTO = "NotoSansCJK-Regular.ttc";
    private static final String ASSET_BITMAP = "8x13.pcf";

    private static final String SRC_DEJAVU = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    private static final String SRC_UBUNTU = "/usr/share/fonts/truetype/ubuntu/Ubuntu[wdth,wght].ttf";
    private static final String SRC_NOTO = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc";
    private static final String SRC_BITMAP =
            "/usr/share/fonts/X11/misc/8x13-ISO8859-1.pcf.gz (decompressed -> 8x13.pcf)";

    private static final String SHA_DEJAVU =
            "ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280";
    private static final String SHA_UBUNTU =
            "28c4c189a44803b1986fd16074187034dc6d94ad35f5e87de13dd0e786b70b73";
    private static final String SHA_NOTO =
            "b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a";
    private static final String SHA_BITMAP =
            "325f347db4ab1da0a461414e62e4e142401b6296e9f519823b139ceaeff82348";

    private static final float[] SIZES = {16.3f, 33.4f};
    private static final String[] V_STRINGS = {"Hello", "AV", "m"};
    private static final String[] V_STR_NAMES = {"Hello", "AV", "m"};
    private static final String[] AXIS_NAMES = {"default", "wght100", "wght700", "wdth75"};
    private static final String[] AXIS_SETTINGS =
            {null, "'wght' 100", "'wght' 700", "'wdth' 75"};
    private static final String F_TEXT = "A\u4E16B";
    private static final String B_TEXT = "Hello";

    private final String runId =
            "font-007-apk-" + Long.toHexString(System.currentTimeMillis());

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
                    + jsonString(describe(t))
                    + "}";
        }
        System.out.println(SENTINEL_BEGIN);
        System.out.println(json);
        System.out.println(SENTINEL_END);
        writeResults(json);
        System.out.println(DONE_MARKER);
    }

    private String runProbe() throws Exception {
        File fileDejavu = new File(getFilesDir(), ASSET_DEJAVU);
        File fileUbuntu = new File(getFilesDir(), ASSET_UBUNTU);
        File fileNoto = new File(getFilesDir(), ASSET_NOTO);
        File fileBitmap = new File(getFilesDir(), ASSET_BITMAP);
        copyAssetToPrivate(ASSET_DEJAVU, fileDejavu);
        copyAssetToPrivate(ASSET_UBUNTU, fileUbuntu);
        copyAssetToPrivate(ASSET_NOTO, fileNoto);
        copyAssetToPrivate(ASSET_BITMAP, fileBitmap);
        String shaDejavu = sha256Hex(fileDejavu);
        String shaUbuntu = sha256Hex(fileUbuntu);
        String shaNoto = sha256Hex(fileNoto);
        String shaBitmap = sha256Hex(fileBitmap);
        String apkSha = sha256Hex(new File(getPackageCodePath()));

        StringBuilder exceptions = new StringBuilder();
        checkSha(exceptions, "DEJAVU", shaDejavu, SHA_DEJAVU);
        checkSha(exceptions, "UBUNTU", shaUbuntu, SHA_UBUNTU);
        checkSha(exceptions, "NOTO", shaNoto, SHA_NOTO);
        checkSha(exceptions, "BITMAP", shaBitmap, SHA_BITMAP);

        // Face loading is itself part of the evidence: a failure becomes the
        // per-cell exception of every cell that needs the face.
        Throwable ubuntuLoadError = null;
        Typeface typefaceUbuntu = null;
        try {
            typefaceUbuntu = Typeface.createFromFile(fileUbuntu);
        } catch (Throwable t) {
            ubuntuLoadError = t;
        }
        if (typefaceUbuntu == null && ubuntuLoadError == null) {
            ubuntuLoadError = new IllegalStateException("Typeface.createFromFile returned null");
        }

        Throwable fallbackError = null;
        Typeface typefaceFallback = null;
        try {
            FontFamily base = new FontFamily.Builder(new Font.Builder(fileDejavu).build()).build();
            FontFamily custom =
                    new FontFamily.Builder(new Font.Builder(fileNoto).build()).build();
            Typeface.CustomFallbackBuilder builder =
                    new Typeface.CustomFallbackBuilder(base);
            builder.addCustomFallback(custom);
            typefaceFallback = builder.build();
            if (typefaceFallback == null) {
                throw new IllegalStateException("CustomFallbackBuilder.build returned null");
            }
        } catch (Throwable t) {
            fallbackError = t;
        }

        Throwable bitmapError = null;
        Typeface typefaceBitmap = null;
        try {
            typefaceBitmap = Typeface.createFromFile(fileBitmap);
            if (typefaceBitmap == null) {
                throw new IllegalStateException("Typeface.createFromFile returned null");
            }
        } catch (Throwable t) {
            bitmapError = t;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(jsonString(TASK_ID));
        sb.append(",\"case_id\":").append(jsonString(CASE_ID));
        sb.append(",\"run_id\":").append(jsonString(runId));
        sb.append(",\"status\":\"").append(exceptions.length() == 0 ? "OK" : "ERROR");
        sb.append("\",\"exceptions\":[").append(exceptions).append(']');
        sb.append(",\"completion\":{\"marker\":").append(jsonString(DONE_MARKER));
        sb.append(",\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END)).append('}');

        sb.append(",\"config\":{\"antiAlias\":true,\"align\":\"LEFT\"");
        sb.append(",\"sizes\":").append(floatListJson(SIZES));
        sb.append(",\"vStrings\":").append(stringListJson(V_STRINGS));
        sb.append(",\"vAxisNames\":").append(stringListJson(AXIS_NAMES));
        sb.append(",\"vAxisSettings\":").append(axisSettingsJson());
        sb.append(",\"fText\":").append(jsonString(F_TEXT));
        sb.append(",\"bText\":").append(jsonString(B_TEXT)).append('}');

        sb.append(",\"assets\":{");
        sb.append("\"dejavu\":").append(assetJson(ASSET_DEJAVU, SRC_DEJAVU, shaDejavu, "primary"));
        sb.append(",\"ubuntu\":").append(assetJson(ASSET_UBUNTU, SRC_UBUNTU, shaUbuntu, "variable"));
        sb.append(",\"noto\":").append(assetJson(ASSET_NOTO, SRC_NOTO, shaNoto, "fallback"));
        sb.append(",\"bitmap\":").append(assetJson(ASSET_BITMAP, SRC_BITMAP, shaBitmap, "bitmap"));
        sb.append('}');
        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');

        sb.append(",\"cells\":[");
        boolean[] first = {true};

        // V via Paint.setFontVariationSettings.
        for (float size : SIZES) {
            for (int s = 0; s < V_STRINGS.length; s++) {
                for (int a = 0; a < AXIS_NAMES.length; a++) {
                    emitVPaintCell(sb, first, size, V_STRINGS[s], V_STR_NAMES[s],
                            AXIS_NAMES[a], AXIS_SETTINGS[a], typefaceUbuntu,
                            ubuntuLoadError, shaUbuntu);
                }
            }
        }

        // V via Typeface.Builder.setFontVariationSettings.
        for (float size : SIZES) {
            for (int s = 0; s < V_STRINGS.length; s++) {
                for (int a = 0; a < AXIS_NAMES.length; a++) {
                    emitVBuilderCell(sb, first, size, V_STRINGS[s], V_STR_NAMES[s],
                            AXIS_NAMES[a], AXIS_SETTINGS[a], fileUbuntu,
                            ubuntuLoadError, shaUbuntu);
                }
            }
        }

        // F fallback chain.
        for (float size : SIZES) {
            emitFaceCell(sb, first, "F", "F_fallback", size, F_TEXT, "noto", shaNoto,
                    typefaceFallback, fallbackError);
        }

        // B bitmap face.
        for (float size : SIZES) {
            emitFaceCell(sb, first, "B", "B_bitmap", size, B_TEXT, "bitmap", shaBitmap,
                    typefaceBitmap, bitmapError);
        }

        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    /** V cell applied through Paint.setFontVariationSettings. */
    private static void emitVPaintCell(StringBuilder sb, boolean[] first, float size, String text,
                                       String strName, String axisName, String settings,
                                       Typeface typeface, Throwable loadError, String fontSha) {
        String label = "V_paint_" + axisName + "_" + strName;
        Paint paint = null;
        String resolution;
        String exception = null;
        Boolean applied = null;
        try {
            if (typeface == null) {
                resolution = "exception";
                exception = describe(loadError);
            } else if (settings == null) {
                paint = newPaint(typeface, size);
                resolution = "value";
            } else {
                Paint candidate = newPaint(typeface, size);
                boolean ok = candidate.setFontVariationSettings(settings);
                applied = ok;
                if (ok) {
                    paint = candidate;
                    resolution = "value";
                } else {
                    // Axis rejected: the paint still measures with the default
                    // face; keep the values but classify the setting ignored.
                    paint = candidate;
                    resolution = "axis_ignored";
                }
            }
        } catch (Throwable t) {
            paint = null;
            resolution = "exception";
            exception = describe(t);
        }
        emitCell(sb, first, "V_paint", label, strName, size, text, "ubuntu", fontSha,
                settings, applied, resolution, paint, exception, echoJson(typeface));
    }

    /** V cell applied through Typeface.Builder.setFontVariationSettings. */
    private static void emitVBuilderCell(StringBuilder sb, boolean[] first, float size, String text,
                                         String strName, String axisName, String settings,
                                         File ubuntuFile, Throwable loadError, String fontSha) {
        String label = "V_builder_" + axisName + "_" + strName;
        Typeface typeface = null;
        Paint paint = null;
        String resolution = "value";
        String exception = null;
        try {
            if (loadError != null) {
                resolution = "exception";
                exception = describe(loadError);
            } else {
                Typeface.Builder builder = new Typeface.Builder(ubuntuFile);
                if (settings != null) {
                    builder.setFontVariationSettings(settings);
                }
                typeface = builder.build();
                if (typeface == null) {
                    resolution = "exception";
                    exception = "Typeface.Builder.build returned null";
                } else {
                    paint = newPaint(typeface, size);
                }
            }
        } catch (Throwable t) {
            typeface = null;
            paint = null;
            resolution = "exception";
            exception = describe(t);
        }
        emitCell(sb, first, "V_builder", label, strName, size, text, "ubuntu", fontSha,
                settings, null, resolution, paint, exception, echoJson(typeface));
    }

    /** A cell backed by one prepared typeface (F fallback / B bitmap). */
    private static void emitFaceCell(StringBuilder sb, boolean[] first, String kind, String label,
                                     float size, String text, String assetKey, String fontSha,
                                     Typeface typeface, Throwable error) {
        Paint paint = null;
        String resolution;
        String exception = null;
        if (typeface == null) {
            resolution = "exception";
            exception = describe(error);
        } else {
            try {
                paint = newPaint(typeface, size);
                resolution = "value";
            } catch (Throwable t) {
                paint = null;
                resolution = "exception";
                exception = describe(t);
            }
        }
        emitCell(sb, first, kind, label, label, size, text, assetKey, fontSha,
                null, null, resolution, paint, exception, echoJson(typeface));
    }

    /**
     * Writes one cell. A {@code null} paint / "exception" resolution yields an
     * empty value block; otherwise the per-glyph advances, sum, measureText and
     * bounds are measured on the given paint.
     */
    private static void emitCell(StringBuilder sb, boolean[] first, String kind, String label,
                                 String name, float size, String text, String assetKey,
                                 String fontSha, String requested, Boolean applied,
                                 String resolution, Paint paint, String exception,
                                 String echoJson) {
        int count = 0;
        float[] widths = new float[0];
        float sum = 0.0f;
        float measure = 0.0f;
        Rect rect = new Rect();
        if (paint != null && !"exception".equals(resolution)) {
            try {
                widths = new float[Math.max(1, text.length())];
                count = paint.getTextWidths(text, widths);
                for (int k = 0; k < count; k++) {
                    sum += widths[k];
                }
                rect = new Rect();
                paint.getTextBounds(text, 0, text.length(), rect);
                measure = paint.measureText(text);
            } catch (Throwable t) {
                count = 0;
                widths = new float[0];
                sum = 0.0f;
                measure = 0.0f;
                rect = new Rect();
                resolution = "exception";
                exception = describe(t);
            }
        }

        if (!first[0]) {
            sb.append(",\n");
        }
        first[0] = false;
        sb.append('{');
        sb.append("\"kind\":").append(jsonString(kind));
        sb.append(",\"label\":").append(jsonString(label));
        sb.append(",\"name\":").append(jsonString(name));
        sb.append(",\"size\":").append(floatJson(size));
        sb.append(",\"text\":").append(jsonString(text));
        sb.append(",\"utf16\":").append(utf16Json(text));
        sb.append(",\"asset\":").append(jsonString(assetKey));
        sb.append(",\"fontSha256\":").append(jsonString(fontSha));
        sb.append(",\"requested\":")
                .append(requested == null ? "null" : jsonString(requested));
        sb.append(",\"applied\":").append(applied == null ? "null" : applied.toString());
        sb.append(",\"resolution\":").append(jsonString(resolution));
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
        sb.append(",\"measureText\":").append(floatPairJson(measure));
        sb.append(",\"bounds\":").append(rectJson(rect));
        sb.append(",\"typefaceEcho\":").append(echoJson);
        sb.append(",\"exception\":")
                .append(exception == null ? "null" : jsonString(exception));
        sb.append('}');
    }

    private static Paint newPaint(Typeface typeface, float size) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        paint.setTextAlign(Paint.Align.LEFT);
        return paint;
    }

    private static String echoJson(Typeface typeface) {
        if (typeface == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"weight\":");
        try {
            sb.append(typeface.getWeight());
        } catch (Throwable t) {
            sb.append(jsonString("ERROR:" + t.getClass().getName()));
        }
        sb.append(",\"italic\":");
        try {
            sb.append(typeface.isItalic());
        } catch (Throwable t) {
            sb.append(jsonString("ERROR:" + t.getClass().getName()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static String assetJson(String asset, String source, String sha, String role) {
        return "{\"asset\":" + jsonString(asset)
                + ",\"sourceName\":" + jsonString(source)
                + ",\"sha256\":" + jsonString(sha)
                + ",\"role\":" + jsonString(role) + '}';
    }

    private static String axisSettingsJson() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < AXIS_NAMES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(AXIS_NAMES[i])).append(':');
            sb.append(AXIS_SETTINGS[i] == null ? "null" : jsonString(AXIS_SETTINGS[i]));
        }
        return sb.append('}').toString();
    }

    private static String stringListJson(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(values[i]));
        }
        return sb.append(']').toString();
    }

    private static String floatListJson(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(floatJson(values[i]));
        }
        return sb.append(']').toString();
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

    private static void checkSha(StringBuilder exceptions, String key, String got, String want) {
        if (!want.equals(got)) {
            appendException(exceptions, key + " font sha mismatch: expected " + want
                    + " got " + got);
        }
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
            byte[] buf = new byte[65536];
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
            byte[] buf = new byte[65536];
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
