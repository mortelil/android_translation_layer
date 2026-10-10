/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.fontglyph;

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
 * FONT-006 (milestone G-A) probe: per-glyph advance rounding, Paint flags and
 * synthetic vs real italic.
 *
 * Records, on the SAME bundled font bytes that both the Android reference and
 * ATL load (each copied to private storage and hashed with SHA-256):
 *
 *   1. size x string matrix: sizes {12.5,16.3,33.4,64.0} x
 *      {"Hello","AV","jgy","iiii","\u2026","m"} on typeface A (DejaVuSans),
 *      anti-alias on, align LEFT.
 *   2. flag variants on typeface A at 33.4f with "Hello": default;
 *      SUBPIXEL_TEXT_FLAG; LINEAR_TEXT_FLAG; both; setHinting FULL/LINEAR/
 *      SLIGHT/NONE; letterSpacing(0.5f).
 *   3. style variants at 33.4f with "Hello": A+fakeBold; A+skew(-0.25);
 *      A+both; B default (DejaVuSans-Oblique, REAL italic); B+skew; B+fakeBold;
 *      Typeface.create(A,BOLD); Typeface.createFromFile(Bold file).
 *
 * Per cell: per-glyph Paint.getTextWidths as Float.toHexString + decimal, their
 * sum, Paint.measureText, Paint.getTextBounds (four ints), the variant label and
 * the loaded font SHA-256. Cells that Android's public API cannot express (e.g.
 * hinting modes without a public constant) stay in the matrix with an explicit
 * observed value (applied=false + exception) rather than being dropped.
 *
 * Emits one sentinel-wrapped JSON object (FONT006_JSON_BEGIN/_END) on stdout,
 * persists the same JSON to filesDir/results.json, then prints FONT006_DONE.
 * Completion is the sentinel / results.json, never process exit (runbook §8).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "FONT006";
    private static final String TASK_ID = "FONT-006";
    private static final String CASE_ID = "FONT-GLYPH";
    private static final String SENTINEL_BEGIN = "FONT006_JSON_BEGIN";
    private static final String SENTINEL_END = "FONT006_JSON_END";
    private static final String DONE_MARKER = "FONT006_DONE";

    private static final String ASSET_A = "DejaVuSans.ttf";
    private static final String ASSET_B = "DejaVuSans-Oblique.ttf";
    private static final String ASSET_BOLD = "DejaVuSans-Bold.ttf";
    private static final String EXPECTED_SHA_A =
            "ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280";
    private static final String EXPECTED_SHA_B =
            "2d7b524ce7d51db78591b4e19c8185b89f3af0aa6534c5e524cf0c6f4ea673de";
    private static final String EXPECTED_SHA_BOLD =
            "5c1247acef7f2b8522a31742c76d6adcb5569bacc0be7ceaa4dc39dd252ce895";

    private static final float[] SIZES = {12.5f, 16.3f, 33.4f, 64.0f};
    private static final String[] STRINGS = {"Hello", "AV", "jgy", "iiii", "\u2026", "m"};
    private static final String[] STRING_NAMES =
            {"Hello", "AV", "jgy", "iiii", "ellipsis", "m"};

    private static final float FLAG_SIZE = 33.4f;
    private static final String FLAG_TEXT = "Hello";

    private static final String[] FLAG_VARIANTS = {
            "default", "subpixel", "linear", "subpixel_linear",
            "hinting_FULL", "hinting_LINEAR", "hinting_SLIGHT", "hinting_NONE",
            "letterSpacing_0.5"
    };

    private static final String[] STYLE_VARIANTS = {
            "A_fakeBold", "A_skew", "A_fakeBold_skew",
            "B_default", "B_skew", "B_fakeBold",
            "A_create_BOLD", "bold_file"
    };

    private final String runId =
            "font-006-apk-" + Long.toHexString(System.currentTimeMillis());

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
        File fileA = new File(getFilesDir(), ASSET_A);
        File fileB = new File(getFilesDir(), ASSET_B);
        File fileBold = new File(getFilesDir(), ASSET_BOLD);
        copyAssetToPrivate(ASSET_A, fileA);
        copyAssetToPrivate(ASSET_B, fileB);
        copyAssetToPrivate(ASSET_BOLD, fileBold);
        String shaA = sha256Hex(fileA);
        String shaB = sha256Hex(fileB);
        String shaBold = sha256Hex(fileBold);
        String apkSha = sha256Hex(new File(getPackageCodePath()));

        Typeface typefaceA = Typeface.createFromFile(fileA);
        Typeface typefaceB = Typeface.createFromFile(fileB);
        Typeface typefaceBoldFile = Typeface.createFromFile(fileBold);
        Typeface typefaceCreateBold = Typeface.create(typefaceA, Typeface.BOLD);

        StringBuilder exceptions = new StringBuilder();
        checkSha(exceptions, "A", shaA, EXPECTED_SHA_A);
        checkSha(exceptions, "B", shaB, EXPECTED_SHA_B);
        checkSha(exceptions, "Bold", shaBold, EXPECTED_SHA_BOLD);

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
        sb.append(",\"sizes\":[");
        for (int i = 0; i < SIZES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(floatJson(SIZES[i]));
        }
        sb.append("],\"strings\":[");
        for (int i = 0; i < STRINGS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(STRINGS[i]));
        }
        sb.append("],\"flagSize\":").append(floatJson(FLAG_SIZE));
        sb.append(",\"flagText\":").append(jsonString(FLAG_TEXT));
        sb.append(",\"flagVariants\":[").append(stringListJson(FLAG_VARIANTS)).append(']');
        sb.append(",\"styleVariants\":[").append(stringListJson(STYLE_VARIANTS)).append(']');
        sb.append('}');

        sb.append(",\"assets\":{");
        sb.append("\"A\":{\"asset\":").append(jsonString(ASSET_A))
                .append(",\"sha256\":").append(jsonString(shaA)).append('}');
        sb.append(",\"B\":{\"asset\":").append(jsonString(ASSET_B))
                .append(",\"sha256\":").append(jsonString(shaB)).append('}');
        sb.append(",\"Bold\":{\"asset\":").append(jsonString(ASSET_BOLD))
                .append(",\"sha256\":").append(jsonString(shaBold)).append('}');
        sb.append('}');
        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');

        sb.append(",\"cells\":[");
        boolean first = true;

        // 1. size x string matrix on typeface A.
        for (float size : SIZES) {
            Paint paint = newPaint(typefaceA, size);
            for (int i = 0; i < STRINGS.length; i++) {
                first = emitCell(sb, first, "size_string", "A_default", STRING_NAMES[i],
                        size, STRINGS[i], "A", shaA, paint, "");
            }
        }

        // 2. flag variants at 33.4f "Hello" on typeface A.
        for (String variant : FLAG_VARIANTS) {
            Paint paint = newPaint(typefaceA, FLAG_SIZE);
            String observed = applyFlag(paint, variant);
            first = emitCell(sb, first, "flag", variant, variant,
                    FLAG_SIZE, FLAG_TEXT, "A", shaA, paint, observed);
        }

        // 3. style variants at 33.4f "Hello".
        for (String variant : STYLE_VARIANTS) {
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setTextSize(FLAG_SIZE);
            paint.setTextAlign(Paint.Align.LEFT);
            String observed = applyStyle(paint, variant, typefaceA, typefaceB,
                    typefaceBoldFile, typefaceCreateBold);
            first = emitCell(sb, first, "style", variant, variant,
                    FLAG_SIZE, FLAG_TEXT, faceOfStyle(variant), shaOfFace(variant, shaA, shaB, shaBold),
                    paint, observed);
        }

        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    /** Builds one cell. {@code observed} is a ready "" or ",\"observed\":{...}". */
    private static boolean emitCell(StringBuilder sb, boolean first, String kind,
                                    String label, String name, float size, String text,
                                    String face, String fontSha, Paint paint,
                                    String observed) {
        float[] widths = new float[Math.max(1, text.length())];
        int count = paint.getTextWidths(text, widths);
        float sum = 0.0f;
        for (int k = 0; k < count; k++) {
            sum += widths[k];
        }
        Rect rect = new Rect();
        paint.getTextBounds(text, 0, text.length(), rect);
        float measure = paint.measureText(text);

        if (!first) {
            sb.append(",\n");
        }
        sb.append('{');
        sb.append("\"kind\":").append(jsonString(kind));
        sb.append(",\"label\":").append(jsonString(label));
        sb.append(",\"name\":").append(jsonString(name));
        sb.append(",\"size\":").append(floatJson(size));
        sb.append(",\"text\":").append(jsonString(text));
        sb.append(",\"utf16\":").append(utf16Json(text));
        sb.append(",\"face\":").append(jsonString(face));
        sb.append(",\"fontSha256\":").append(jsonString(fontSha));
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
        sb.append(observed);
        sb.append('}');
        return false;
    }

    private static Paint newPaint(Typeface typeface, float size) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        paint.setTextAlign(Paint.Align.LEFT);
        return paint;
    }

    /** Applies a flag variant; returns the observed-state JSON fragment. */
    private static String applyFlag(Paint paint, String variant) {
        if (variant.equals("default")) {
            return observedFlags(paint, variant, 0, true, null);
        }
        if (variant.equals("subpixel")) {
            paint.setFlags(paint.getFlags() | Paint.SUBPIXEL_TEXT_FLAG);
            return observedFlags(paint, variant, 0, true, null);
        }
        if (variant.equals("linear")) {
            paint.setFlags(paint.getFlags() | Paint.LINEAR_TEXT_FLAG);
            return observedFlags(paint, variant, 0, true, null);
        }
        if (variant.equals("subpixel_linear")) {
            paint.setFlags(paint.getFlags()
                    | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
            return observedFlags(paint, variant, 0, true, null);
        }
        if (variant.equals("letterSpacing_0.5")) {
            paint.setLetterSpacing(0.5f);
            return observedFlags(paint, variant, 0, true, null);
        }
        // Hinting requests. Android's public Paint only defines HINTING_OFF and
        // HINTING_ON; the other requested modes have no public constant, so the
        // closest integer is attempted and the observed result recorded.
        int value;
        if (variant.equals("hinting_FULL")) {
            value = Paint.HINTING_ON;
        } else if (variant.equals("hinting_NONE")) {
            value = Paint.HINTING_OFF;
        } else if (variant.equals("hinting_LINEAR")) {
            value = 2;
        } else if (variant.equals("hinting_SLIGHT")) {
            value = 3;
        } else {
            return observedFlags(paint, variant, 0, false, "unknown variant");
        }
        boolean applied = true;
        String exc = null;
        try {
            paint.setHinting(value);
        } catch (Throwable t) {
            applied = false;
            exc = t.getClass().getName() + ": " + t.getMessage();
        }
        return observedFlags(paint, variant, value, applied, exc);
    }

    private static String observedFlags(Paint paint, String variant, int requestedValue,
                                        boolean applied, String exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(",\"observed\":{");
        sb.append("\"requestedMode\":").append(jsonString(variant));
        sb.append(",\"requestedValue\":").append(requestedValue);
        sb.append(",\"applied\":").append(applied);
        sb.append(",\"flags\":").append(paint.getFlags());
        sb.append(",\"subpixelText\":").append(paint.isSubpixelText());
        sb.append(",\"linearText\":").append(paint.isLinearText());
        sb.append(",\"hinting\":").append(paint.getHinting());
        sb.append(",\"letterSpacing\":").append(floatJson(paint.getLetterSpacing()));
        sb.append(",\"exception\":")
                .append(exception == null ? "null" : jsonString(exception));
        sb.append('}');
        return sb.toString();
    }

    /** Applies a style variant; returns the observed-state JSON fragment. */
    private static String applyStyle(Paint paint, String variant, Typeface typefaceA,
                                     Typeface typefaceB, Typeface typefaceBoldFile,
                                     Typeface typefaceCreateBold) {
        String baseFace;
        String derived = "none";
        if (variant.equals("A_fakeBold")) {
            paint.setTypeface(typefaceA);
            paint.setFakeBoldText(true);
            baseFace = "A";
        } else if (variant.equals("A_skew")) {
            paint.setTypeface(typefaceA);
            paint.setTextSkewX(-0.25f);
            baseFace = "A";
        } else if (variant.equals("A_fakeBold_skew")) {
            paint.setTypeface(typefaceA);
            paint.setFakeBoldText(true);
            paint.setTextSkewX(-0.25f);
            baseFace = "A";
        } else if (variant.equals("B_default")) {
            paint.setTypeface(typefaceB);
            baseFace = "B";
        } else if (variant.equals("B_skew")) {
            paint.setTypeface(typefaceB);
            paint.setTextSkewX(-0.25f);
            baseFace = "B";
        } else if (variant.equals("B_fakeBold")) {
            paint.setTypeface(typefaceB);
            paint.setFakeBoldText(true);
            baseFace = "B";
        } else if (variant.equals("A_create_BOLD")) {
            paint.setTypeface(typefaceCreateBold);
            baseFace = "A";
            derived = "create_BOLD";
        } else if (variant.equals("bold_file")) {
            paint.setTypeface(typefaceBoldFile);
            baseFace = "Bold";
            derived = "file";
        } else {
            paint.setTypeface(typefaceA);
            baseFace = "A";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(",\"observed\":{");
        sb.append("\"baseFace\":").append(jsonString(baseFace));
        sb.append(",\"derived\":").append(jsonString(derived));
        sb.append(",\"fakeBold\":").append(paint.isFakeBoldText());
        sb.append(",\"skewX\":").append(floatJson(paint.getTextSkewX()));
        sb.append(",\"letterSpacing\":").append(floatJson(paint.getLetterSpacing()));
        sb.append('}');
        return sb.toString();
    }

    private static String faceOfStyle(String variant) {
        if (variant.startsWith("B_")) {
            return "B";
        }
        if (variant.equals("bold_file")) {
            return "Bold";
        }
        return "A";
    }

    private static String shaOfFace(String variant, String shaA, String shaB, String shaBold) {
        String face = faceOfStyle(variant);
        if (face.equals("B")) {
            return shaB;
        }
        if (face.equals("Bold")) {
            return shaBold;
        }
        return shaA;
    }

    private static void checkSha(StringBuilder exceptions, String key, String got, String want) {
        if (!want.equals(got)) {
            appendException(exceptions, key + " font sha mismatch: expected " + want
                    + " got " + got);
        }
    }

    private static String stringListJson(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(values[i]));
        }
        return sb.toString();
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
