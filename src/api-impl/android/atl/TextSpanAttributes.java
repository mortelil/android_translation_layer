// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.graphics.Paint;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.MetricAffectingSpan;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Attributes understood by the Pango backend, using UTF-8 byte ranges. */
public final class TextSpanAttributes {
	private TextSpanAttributes() {}

	/** ATL_DEBUG_TEXT_STYLE, as for the native [atl-text-style] dump: prints every span the app
	 * put on the text, the paint metric state it asks Layout to measure and draw with, and which
	 * of those requests this encoder can carry into the Pango layout. */
	private static final boolean DEBUG = "1".equals(System.getenv("ATL_DEBUG_TEXT_STYLE"));

	private static void log(String message) {
		if (DEBUG) Log.i("ATLTextStyles", message);
	}

	/** The metric state SPAN asks a TextPaint for, as android.text.Layout applies it per span: the
	 * probe starts empty, so every value printed is the span's own request. */
	private static String describe(MetricAffectingSpan span) {
		TextPaint probe = new TextPaint();
		probe.setTextSize(0);
		try {
			span.updateMeasureState(probe);
		} catch (Throwable throwable) {
			return span.getClass().getName() + " threw " + throwable;
		}
		return span.getClass().getName() + " letterSpacing=" + probe.getLetterSpacing()
			+ " textSize=" + probe.getTextSize() + " fakeBold=" + probe.isFakeBoldText();
	}

	public static int[] encode(CharSequence text, float density) {
		ArrayList<Integer> values = new ArrayList<>();
		if (text instanceof Spanned) {
			Spanned spanned = (Spanned)text;
			String string = text.toString();
			for (Object span : spanned.getSpans(0, text.length(), Object.class)) {
				int type, value;
				if (span instanceof ForegroundColorSpan) {
					type = 1; value = ((ForegroundColorSpan)span).getForegroundColor();
				} else if (span instanceof AbsoluteSizeSpan) {
					AbsoluteSizeSpan size = (AbsoluteSizeSpan)span;
					type = 2; value = Float.floatToIntBits(size.getSize() * (size.getDip() ? density : 1));
				} else {
					if (DEBUG) log("dropped " + (span instanceof MetricAffectingSpan
						? describe((MetricAffectingSpan)span) : span.getClass().getName())
						+ " range=" + spanned.getSpanStart(span) + ".." + spanned.getSpanEnd(span));
					continue;
				}
				int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
				if (start < 0 || end <= start || end > text.length()) continue;
				values.add(string.substring(0, start).getBytes(StandardCharsets.UTF_8).length);
				values.add(string.substring(0, end).getBytes(StandardCharsets.UTF_8).length);
				values.add(type); values.add(value);
			}
		}
		int[] result = new int[values.size()];
		for (int i = 0; i < result.length; i++) result[i] = values.get(i);
		return result;
	}
}
