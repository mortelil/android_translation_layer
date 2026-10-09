// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.AbsoluteSizeSpan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Attributes understood by the Pango backend, using UTF-8 byte ranges. */
public final class TextSpanAttributes {
	private TextSpanAttributes() {}
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
				} else continue;
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
