package android.text.style;

import android.graphics.drawable.Drawable;

public abstract class DynamicDrawableSpan extends ReplacementSpan {
	public static final int ALIGN_BOTTOM = 0;
	public static final int ALIGN_BASELINE = 1;
	public static final int ALIGN_CENTER = 2;

	private final int verticalAlignment;

	public DynamicDrawableSpan() {
		this(ALIGN_BOTTOM);
	}

	public DynamicDrawableSpan(int verticalAlignment) {
		this.verticalAlignment = verticalAlignment;
	}

	public int getVerticalAlignment() {
		return verticalAlignment;
	}

	public abstract Drawable getDrawable();
}
