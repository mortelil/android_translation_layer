package android.graphics;

import java.util.Locale;

public class Paint {
	public static final int ANTI_ALIAS_FLAG = (1 << 0);
	public static final int FILTER_BITMAP_FLAG = (1 << 1);
	public static final int DITHER_FLAG = (1 << 2);
	public static final int UNDERLINE_TEXT_FLAG = (1 << 3);
	public static final int STRIKE_THRU_TEXT_FLAG = (1 << 4);
	public static final int FAKE_BOLD_TEXT_FLAG = (1 << 5);
	public static final int LINEAR_TEXT_FLAG = (1 << 6);
	public static final int SUBPIXEL_TEXT_FLAG = (1 << 7);
	public static final int DEV_KERN_TEXT_FLAG = (1 << 8);
	public static final int LCD_RENDER_TEXT_FLAG = (1 << 9);
	public static final int EMBEDDED_BITMAP_TEXT_FLAG = (1 << 10);
	public static final int AUTO_HINTING_TEXT_FLAG = (1 << 11);
	public static final int VERTICAL_TEXT_FLAG = (1 << 12);

	public static final int HINTING_OFF = 0x0;
	public static final int HINTING_ON = 0x1;

	public long paint; // native paint
	private Typeface typeface;
	private Xfermode xfermode;
	private Shader shader;
	private Align align = Align.CENTER;
	private ColorFilter color_filter;
	// Android's default Paint flags (ANTI_ALIAS|FILTER_BITMAP|DEV_KERN|EMBEDDED_BITMAP); the
	// subpixel/linear bits added by setFlags() are what the text measurement consults.
	private int flags = ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG | DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG;
	private int hinting = HINTING_ON;
	/* hwui Paint's mLetterSpacing/mTextSkewX default to 0 (Paint.h) and are only inputs to text
	 * measurement; reset() puts them back to 0. */
	private float letterSpacing = 0;
	private float textSkewX = 0;

	public Paint() {
		paint = native_create();
	}

	public Paint(int flags) {
		this();
		setFlags(flags);
	}

	public Paint(Paint paint) {
		this.paint = native_clone(paint.paint);
		this.typeface = paint.typeface;
		this.flags = paint.flags;
		this.hinting = paint.hinting;
		this.letterSpacing = paint.letterSpacing;
		this.textSkewX = paint.textSkewX;
	}

	@Override protected void finalize() throws Throwable {
		try { if (paint != 0) native_recycle(paint); }
		finally { super.finalize(); }
	}

	public void setColor(int color) {
		native_set_color(paint, color);
	}

	public void setARGB(int a, int r, int g, int b) {
		setColor(Color.argb(a, r, g, b));
	}

	public int getColor() {
		return native_get_color(paint);
	}

	public void setAlpha(int a) {
		native_set_alpha(paint, a);
	}

	public int getAlpha() {
		return native_get_alpha(paint);
	}

	public void setAntiAlias(boolean aa) {
	}

	public boolean isAntiAlias() {
		return true;
	}

	public boolean setFontVariationSettings(String fvs) {
		return true;
	}

	public void setStrokeWidth(float width) {
		native_set_stroke_width(paint, width);
	}
	public void setTextSize(float size) {
		native_set_text_size(paint, size);
	}

	public Typeface setTypeface(Typeface typeface) {
		this.typeface = typeface;
		native_set_typeface(paint, (typeface == null ? Typeface.DEFAULT : typeface).native_instance);
		return typeface;
	}
	public void getTextBounds(String text, int start, int end, Rect bounds) {
		if (end > text.length())
			end = text.length();
		native_get_text_bounds(paint, text.substring(start, end), bounds, flags, hinting,
				(typeface == null ? Typeface.DEFAULT : typeface).getWeight(), letterSpacing, textSkewX);
	}
	public void getTextBounds(char[] text, int index, int count, Rect bounds) {
		native_get_text_bounds(paint, new String(text, index, count), bounds, flags, hinting,
				(typeface == null ? Typeface.DEFAULT : typeface).getWeight(), letterSpacing, textSkewX);
	}
	public int getTextWidths(String text, float[] widths) {
		return getTextWidths(text, 0, text.length(), widths);
	}
	public int getTextWidths(String text, int start, int end, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		if (end - start > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (start == end)
			return 0;
		native_get_text_widths(paint, text.substring(start, end), widths, flags, hinting, letterSpacing, textSkewX);
		return end - start;
	}
	public int getTextWidths(char[] text, int index, int count, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count) < 0 || index + count > text.length || count > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (count == 0)
			return 0;
		native_get_text_widths(paint, new String(text, index, count), widths, flags, hinting, letterSpacing, textSkewX);
		return count;
	}
	public int getTextWidths(CharSequence text, int start, int end, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		if (end - start > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (start == end)
			return 0;
		native_get_text_widths(paint, text.subSequence(start, end).toString(), widths, flags, hinting, letterSpacing, textSkewX);
		return end - start;
	}
	public void setFilterBitmap(boolean filter) {}

	public int getFlags() { return flags; }

	public void setFlags(int flags) {
		this.flags = flags;
		if ((flags & ANTI_ALIAS_FLAG) != 0)
			setAntiAlias(true);
	}

	public int getHinting() { return hinting; }
	public void setHinting(int mode) {
		// Android maps HINTING_OFF -> SkFontHinting::kNone and every other value -> kNormal.
		hinting = (mode == HINTING_OFF) ? HINTING_OFF : HINTING_ON;
	}
	public void setLinearText(boolean linearText) {
		if (linearText)
			flags |= LINEAR_TEXT_FLAG;
		else
			flags &= ~LINEAR_TEXT_FLAG;
	}
	public boolean isLinearText() { return (flags & LINEAR_TEXT_FLAG) != 0; }
	public void setSubpixelText(boolean subpixelText) {
		if (subpixelText)
			flags |= SUBPIXEL_TEXT_FLAG;
		else
			flags &= ~SUBPIXEL_TEXT_FLAG;
	}
	public boolean isSubpixelText() { return (flags & SUBPIXEL_TEXT_FLAG) != 0; }

	public void setStyle(Style style) {
		native_set_style(paint, style.ordinal());
	}

	public float ascent() {
		return -getTextSize();
	}

	public float measureText(char[] text, int index, int count) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count) < 0 || index + count > text.length)
			throw new ArrayIndexOutOfBoundsException();
		return (float)Math.ceil(sumTextWidths(new String(text, index, count)));
	}
	public float measureText(String text, int start, int end) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		return (float)Math.ceil(sumTextWidths(text.substring(start, end)));
	}
	public float measureText(String text) {
		return measureText(text, 0, text.length());
	}
	public float measureText(CharSequence text, int start, int end) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		return (float)Math.ceil(sumTextWidths(text.subSequence(start, end).toString()));
	}

	/* Android's measureText is ceil() of the summed advances nGetTextAdvances reports; the same
	 * advances fill getTextWidths, so the sum must be taken over the widths array. */
	private float sumTextWidths(String text) {
		if (text.length() == 0)
			return 0f;
		float[] widths = new float[text.length()];
		int count = getTextWidths(text, widths);
		float sum = 0f;
		for (int i = 0; i < count; i++)
			sum += widths[i];
		return sum;
	}

	public ColorFilter setColorFilter(ColorFilter colorFilter) {
		if (colorFilter instanceof PorterDuffColorFilter) {
			PorterDuffColorFilter porterDuff = (PorterDuffColorFilter)colorFilter;
			native_set_color_filter(paint, porterDuff.getMode().ordinal(), porterDuff.getColor());
		} else {
			native_set_color_filter(paint, -1, 0);
		}
		color_filter = colorFilter;
		return colorFilter;
	}

	public ColorFilter getColorFilter() {
		return color_filter;
	}

	public Shader setShader(Shader shader) {
		this.shader = shader;
		return shader;
	}

	public enum Style {
		/**
		 * Geometry and text drawn with this style will be filled, ignoring all
		 * stroke-related settings in the paint.
		 */
		FILL,
		/**
		 * Geometry and text drawn with this style will be stroked, respecting
		 * the stroke-related fields on the paint.
		 */
		STROKE,
		/**
		 * Geometry and text drawn with this style will be both filled and
		 * stroked at the same time, respecting the stroke-related fields on
		 * the paint. This mode can give unexpected results if the geometry
		 * is oriented counter-clockwise. This restriction does not apply to
		 * either FILL or STROKE.
		 */
		FILL_AND_STROKE;

		public static final Style values[] = Style.values();
	}

	public static class FontMetrics {
		/**
		 * The maximum distance above the baseline for the tallest glyph in
		 * the font at a given text size.
		 */
		public float top;
		/**
		 * The recommended distance above the baseline for singled spaced text.
		 */
		public float ascent;
		/**
		 * The recommended distance below the baseline for singled spaced text.
		 */
		public float descent;
		/**
		 * The maximum distance below the baseline for the lowest glyph in
		 * the font at a given text size.
		 */
		public float bottom;
		/**
		 * The recommended additional space to add between lines of text.
		 */
		public float leading;
	}

	public static class FontMetricsInt {
		public int top;
		public int ascent;
		public int descent;
		public int bottom;
		public int leading;

		@Override
		public String toString() {
			return "FontMetricsInt: top=" + top + " ascent=" + ascent + " descent=" + descent + " bottom=" + bottom + " leading=" + leading;
		}
	}

	public /*native*/ void setDither(boolean dither) {}
	public boolean isDither() {
		return false;
	}

	public /*native*/ void setUnderlineText(boolean underlineText) {}
	public /*native*/ void setStrikeThruText(boolean strikeThruText) {}
	public void setFakeBoldText(boolean fakeBoldText) {
		// Android's FAKE_BOLD_TEXT_FLAG is what hwui turns into SkFont::setEmbolden().
		if (fakeBoldText)
			flags |= FAKE_BOLD_TEXT_FLAG;
		else
			flags &= ~FAKE_BOLD_TEXT_FLAG;
	}

	public float getStrokeWidth() {
		return native_get_stroke_width(paint);
	}

	public /*native*/ float getStrokeMiter() { return 0; }
	public /*native*/ void setStrokeMiter(float miter) {}
	public /*native*/ float getTextSize() {
		return native_get_text_size(paint);
	}

	public /*native*/ float getTextScaleX() { return 0; }
	public /*native*/ void setTextScaleX(float scaleX) {}
	public float getTextSkewX() { return textSkewX; }
	public void setTextSkewX(float skewX) { this.textSkewX = skewX; }

	public /*native*/ float descent() { return 0; }
	public float getFontMetrics(FontMetrics metrics) {
		return native_get_font_metrics(paint, metrics);
	}
	public int getFontMetricsInt(FontMetricsInt fmi) {
		return native_get_font_metrics_int(paint, fmi);
	}

	public void setShadowLayer(float radius, float dx, float dy, int color) {}

	public Xfermode setXfermode(Xfermode xfermode) {
		this.xfermode = xfermode;
		return xfermode;
	}

	public Xfermode getXfermode() {
		return xfermode;
	}

	public void setBlendMode(BlendMode blendmode) {
		setXfermode(blendmode == null ? null : blendmode.getXfermode());
	}

	public BlendMode getBlendMode() {
		if (this.xfermode instanceof PorterDuffXfermode) {
			return BlendMode.fromValue(((PorterDuffXfermode)this.xfermode).porterDuffMode);
		}
		return null;
	}

	public void setLetterSpacing(float spacing) { this.letterSpacing = spacing; }

	public enum Cap {
		/**
		 * The stroke ends with the path, and does not project beyond it.
		 */
		BUTT,
		/**
		 * The stroke projects out as a semicircle, with the center at the
		 * end of the path.
		 */
		ROUND,
		/**
		 * The stroke projects out as a square, with the center at the end
		 * of the path.
		 */
		SQUARE;

		public static final Cap values[] = Cap.values();
	}

	public enum Join {
		/**
		 * The outer edges of a join meet at a sharp angle
		 */
		MITER,
		/**
		 * The outer edges of a join meet in a circular arc.
		 */
		ROUND,
		/**
		 * The outer edges of a join meet with a straight line
		 */
		BEVEL;

		public static final Join values[] = Join.values();
	}

	public enum Align {
		LEFT,
		CENTER,
		RIGHT,
	}

	public void setStrokeCap(Cap cap) {
		native_set_stroke_cap(paint, cap.ordinal());
	}

	public void setStrokeJoin(Join join) {
		native_set_stroke_join(paint, join.ordinal());
	}

	public Typeface getTypeface() {
		return typeface;
	}

	public void setTextAlign(Align align) {
		this.align = align;
		native_set_text_align(paint, align.ordinal());
	}

	public Shader getShader() {
		return shader;
	}

	public PathEffect getPathEffect() {
		return new PathEffect();
	}

	public PathEffect setPathEffect(PathEffect effect) {
		return effect;
	}

	public int breakText(char[] text, int index, int count, float maxWidth, float[] measuredWidth) { return 10; }
	public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) { return 10; }
	public int breakText(CharSequence text, int start, int end, boolean measureForwards, float maxWidth, float[] measuredWidth) { return 10; }

	public void clearShadowLayer() {}

	public boolean hasShadowLayer() {
		return false;
	}

	public FontMetrics getFontMetrics() {
		FontMetrics metrics = new FontMetrics();
		native_get_font_metrics(paint, metrics);
		return metrics;
	}

	public void setFontMetricsInt(FontMetricsInt fmi) {}

	public FontMetricsInt getFontMetricsInt() {
		FontMetricsInt fmi = new FontMetricsInt();
		native_get_font_metrics_int(paint, fmi);
		return fmi;
	}

	public void set(Paint paint) {
		if (paint == this) return;
		native_recycle(this.paint);
		this.paint = native_clone(paint.paint);
		this.typeface = paint.typeface;
	}

	public boolean isFilterBitmap() { return false; }

	public Cap getStrokeCap() {
		return Cap.values[native_get_stroke_cap(paint)];
	}

	public Join getStrokeJoin() {
		return Join.values[native_get_stroke_join(paint)];
	}

	public Locale getTextLocale() { return Locale.getDefault(); }

	public float getLetterSpacing() { return letterSpacing; }

	public Style getStyle() {
		return Style.values[native_get_style(paint)];
	}

	public Align getTextAlign() {
		return align;
	}

	public boolean hasGlyph(String text) { return false; }

	public MaskFilter setMaskFilter(MaskFilter filter) { return filter; }

	public boolean isFakeBoldText() {
		return (flags & FAKE_BOLD_TEXT_FLAG) != 0;
	}

	public void setFontFeatureSettings(String settings) {}

	public boolean getFillPath(Path src, Path dst) {
		return false;
	}

	public void reset() {
		native_recycle(paint);
		paint = native_create();
		typeface = null;
		xfermode = null;
		shader = null;
		align = Align.CENTER;
		color_filter = null;
		flags = ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG | DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG;
		hinting = HINTING_ON;
		letterSpacing = 0;
		textSkewX = 0;
	}

	private static native float native_get_text_widths(long paint, String text, float[] widths, int flags, int hinting, float letterSpacing, float textSkewX);
	private static native float native_text_advance(long paint, String text);
	private static native void native_set_typeface(long paint, long typeface);
	private static native long native_create();
	private static native long native_clone(long paint);
	private static native void native_recycle(long paint);
	private static native void native_set_color(long paint, int color);
	private static native int native_get_color(long paint);
	private static native void native_set_alpha(long paint, int alpha);
	private static native int native_get_alpha(long paint);
	private static native void native_set_style(long paint, int style);
	private static native int native_get_style(long paint);
	private static native void native_set_stroke_width(long paint, float width);
	private static native float native_get_stroke_width(long paint);
	private static native void native_set_stroke_cap(long paint, int cap);
	private static native int native_get_stroke_cap(long paint);
	private static native void native_set_stroke_join(long paint, int join);
	private static native int native_get_stroke_join(long paint);
	private static native void native_set_text_size(long paint, float size);
	private static native float native_get_text_size(long paint);
	private static native void native_set_color_filter(long paint, int mode, int color);
	private static native void native_get_text_bounds(long paint, String text, Rect bounds, int flags, int hinting, int typefaceWeight, float letterSpacing, float textSkewX);
	private static native void native_set_text_align(long paint, int align);
	private static native float native_get_font_metrics(long paint, FontMetrics metrics);
	private static native int native_get_font_metrics_int(long paint, FontMetricsInt metrics);
}
