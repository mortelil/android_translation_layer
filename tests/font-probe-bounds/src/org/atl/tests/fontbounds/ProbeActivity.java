/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.fontbounds;

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
import java.util.ArrayList;
import java.util.List;

/**
 * FONT-BOUNDS probe: Paint.getTextBounds(String,int,int,Rect) /
 * Paint.getTextBounds(char[],int,int,Rect) ink-box LEFT/TOP/RIGHT/BOTTOM for
 * one identical bundled font file (DejaVuSans.ttf), the fractional text sizes
 * and the three Paint.Align values fixed by spec-FONT-BOUNDS.md.
 *
 * Unlike the FONT-003 metrics probe (which observes width/height only), this
 * probe records the four signed int ink-box fields per cell, the alignment
 * used, the on-device font SHA-256 and each cell's UTF-16 code units, so a
 * wrong left/top can be caught.
 *
 * Emits a single sentinel-wrapped JSON object (FONT004_JSON_BEGIN/_END) on
 * stdout, persists the same JSON to files/results.json, and finally prints the
 * completion marker FONT004_DONE. Completion is the marker / results.json,
 * never process exit (see runbook section 8).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "FONT004";
    private static final String TASK_ID = "FONT-004";
    private static final String CASE_ID = "FONT-BOUNDS";
    private static final String FONT_ASSET = "DejaVuSans.ttf";
    private static final String EXPECTED_SHA256 =
            "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954";
    private static final float[] TEXT_SIZES = {16.3f, 33.4f, 64.0f};
    private static final String SENTINEL_BEGIN = "FONT004_JSON_BEGIN";
    private static final String SENTINEL_END = "FONT004_JSON_END";
    private static final String DONE_MARKER = "FONT004_DONE";

    /** The nine controlled strings of the frozen matrix (see spec-FONT-BOUNDS.md). */
    private static final String[] STRINGS = {
            "",
            " ",
            "Hello",
            "AV",
            "jgy",
            "\u2026",          // horizontal ellipsis, U+2026
            "j",               // negative left-bearing glyph (DejaVu Sans xMin = -37/2048 em)
            "e\u0301",         // e + COMBINING ACUTE ACCENT (2 UTF-16 code units)
            "\uD835\uDD4F"     // U+1D54F MATHEMATICAL DOUBLE-STRUCK CAPITAL X (surrogate pair)
    };
    private static final String[] NAMES = {
            "empty", "space", "Hello", "AV", "jgy",
            "ellipsis", "negative_bearing_j", "combining_e_acute", "nonbmp_u1d54f"
    };

    private final String runId =
            "font-004-apk-" + Long.toHexString(System.currentTimeMillis());

    private static final class Cell {
        final float size;
        final String align;
        final String overload;
        final String name;
        final String text;
        final int start;
        final int end;
        final int[] utf16;
        int left, top, right, bottom;

        Cell(float size, String align, String overload, String name, String text,
             int start, int end, int[] utf16) {
            this.size = size;
            this.align = align;
            this.overload = overload;
            this.name = name;
            this.text = text;
            this.start = start;
            this.end = end;
            this.utf16 = utf16;
        }
    }

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

        List<Cell> cells = buildMatrix();
        for (Cell cell : cells) {
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setTextSize(cell.size);
            paint.setTypeface(typeface);
            paint.setTextAlign(alignment(cell.align));
            Rect rect = new Rect();
            if ("chararray".equals(cell.overload)) {
                char[] chars = cell.text.toCharArray();
                paint.getTextBounds(chars, cell.start, cell.end - cell.start, rect);
            } else {
                paint.getTextBounds(cell.text, cell.start, cell.end, rect);
            }
            cell.left = rect.left;
            cell.top = rect.top;
            cell.right = rect.right;
            cell.bottom = rect.bottom;
            if (cell.left > cell.right || cell.top > cell.bottom) {
                appendException(exceptions, "invalid rect for " + cell.name
                        + ": " + rect.left + "," + rect.top + ","
                        + rect.right + "," + rect.bottom);
            }
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
            sb.append(doubleJson(TEXT_SIZES[i]));
        }
        sb.append("],\"alignments\":[\"LEFT\",\"CENTER\",\"RIGHT\"]}");
        sb.append(",\"completion\":{\"marker\":").append(jsonString(DONE_MARKER));
        sb.append(",\"sentinelBegin\":").append(jsonString(SENTINEL_BEGIN));
        sb.append(",\"sentinelEnd\":").append(jsonString(SENTINEL_END)).append('}');
        sb.append(",\"font\":{\"sha256\":").append(jsonString(fontSha));
        sb.append(",\"asset\":").append(jsonString(FONT_ASSET)).append('}');
        sb.append(",\"apk\":{\"sha256\":").append(jsonString(apkSha)).append('}');
        sb.append(",\"bounds\":[\n");
        boolean first = true;
        for (Cell cell : cells) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append('{');
            sb.append("\"size\":").append(doubleJson(cell.size));
            sb.append(",\"align\":").append(jsonString(cell.align));
            sb.append(",\"overload\":").append(jsonString(cell.overload));
            sb.append(",\"name\":").append(jsonString(cell.name));
            sb.append(",\"text\":").append(jsonString(cell.text));
            sb.append(",\"start\":").append(cell.start);
            sb.append(",\"end\":").append(cell.end);
            sb.append(",\"utf16\":[");
            for (int i = 0; i < cell.utf16.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell.utf16[i]);
            }
            sb.append(']');
            sb.append(",\"left\":").append(cell.left);
            sb.append(",\"top\":").append(cell.top);
            sb.append(",\"right\":").append(cell.right);
            sb.append(",\"bottom\":").append(cell.bottom);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * The frozen cell matrix. Order is deterministic; identity is
     * (size, align, overload, text, start, end).
     */
    private static List<Cell> buildMatrix() {
        List<Cell> cells = new ArrayList<>();
        // All nine strings, LEFT alignment, at each of the three sizes.
        for (float size : TEXT_SIZES) {
            for (int i = 0; i < STRINGS.length; i++) {
                cells.add(cell(size, "LEFT", "string", NAMES[i], STRINGS[i], 0,
                        STRINGS[i].length()));
            }
        }
        // Alignment sweep on "Hello" and "jgy" (CENTER/RIGHT), each size.
        for (float size : TEXT_SIZES) {
            for (String align : new String[]{"CENTER", "RIGHT"}) {
                cells.add(cell(size, align, "string", "Hello", "Hello", 0, 5));
                cells.add(cell(size, align, "string", "jgy", "jgy", 0, 3));
            }
        }
        // Substring range (1, len-1) == "ell" of "Hello", LEFT, each size.
        for (float size : TEXT_SIZES) {
            cells.add(cell(size, "LEFT", "string", "Hello_range_1_4", "Hello", 1, 4));
        }
        // char[] overload for "Hello" (index 0, count 5), LEFT, at 33.4f.
        cells.add(cell(33.4f, "LEFT", "chararray", "Hello_chararray", "Hello", 0, 5));
        return cells;
    }

    private static Cell cell(float size, String align, String overload, String name,
                             String text, int start, int end) {
        return new Cell(size, align, overload, name, text, start, end, utf16(text));
    }

    private static int[] utf16(String s) {
        int[] units = new int[s.length()];
        for (int i = 0; i < s.length(); i++) {
            units[i] = s.charAt(i);
        }
        return units;
    }

    private static Paint.Align alignment(String name) {
        if ("CENTER".equals(name)) {
            return Paint.Align.CENTER;
        }
        if ("RIGHT".equals(name)) {
            return Paint.Align.RIGHT;
        }
        return Paint.Align.LEFT;
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
