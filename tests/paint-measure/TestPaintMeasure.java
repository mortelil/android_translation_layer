// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.paintmeasure;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Public API contract; identical source runs on Android and ATL. */
public class TestPaintMeasure {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void run() {
        int cases = 0;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (Typeface face : new Typeface[] {Typeface.SANS_SERIF, Typeface.MONOSPACE}) {
            paint.setTypeface(face);
            for (float size : new float[] {7.25f, 13.5f, 31.125f, 64f}) {
                paint.setTextSize(size);
                for (String text : new String[] {"", " ", "Hello", "AV", "iiii", "A\u0301", "\u0627\u0644", "A\ud83d\ude00B"}) {
                    float width = paint.measureText(text);
                    check(!Float.isNaN(width) && !Float.isInfinite(width) && width >= 0 && width == Math.ceil(width),
                        "non-integral width: size=" + size + " text=" + text + " width=" + width);
                    String padded = "xx" + text + "zz";
                    check(paint.measureText(padded, 2, 2 + text.length()) == width, "String range");
                    check(paint.measureText(padded.toCharArray(), 2, text.length()) == width, "char[] range");
                    check(paint.measureText(new StringBuilder(padded), 2, 2 + text.length()) == width, "CharSequence range");
                    if (text.isEmpty()) check(width == 0, "empty range");
                    cases++;
                }
            }
        }
        System.out.println("PAINT_MEASURE_PASS cases=" + cases);
    }
}
