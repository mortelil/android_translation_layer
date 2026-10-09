// SPDX-License-Identifier: GPL-3.0-only
package android.text.style;

import android.graphics.Typeface;
import android.text.TextPaint;
import java.util.Objects;

public class TypefaceSpan extends MetricAffectingSpan {
	private final String family;
	private final Typeface typeface;
	public TypefaceSpan(String family) { this.family = family; typeface = null; }
	public TypefaceSpan(Typeface typeface) {
		this.typeface = Objects.requireNonNull(typeface);
		family = null;
	}
	public String getFamily() { return family; }
	public Typeface getTypeface() { return typeface; }
	public void updateDrawState(TextPaint paint) { apply(paint); }
	@Override public void updateMeasureState(TextPaint paint) { apply(paint); }
	private void apply(TextPaint paint) {
		if (typeface != null) paint.setTypeface(typeface);
		else if (family != null) {
			Typeface previous = paint.getTypeface();
			paint.setTypeface(Typeface.create(family, previous == null ? Typeface.NORMAL : previous.getStyle()));
		}
	}
}
