package android.widget;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.BoringLayout;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.BaseMovementMethod;
import android.text.method.KeyListener;
import android.text.method.MovementMethod;
import android.text.method.TransformationMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;

public class TextView extends View {
	/** Saved editable state: the text content (public API contract). */
	public static class SavedState extends android.view.AbsSavedState implements android.os.Parcelable {
		public final CharSequence text;
		public SavedState(CharSequence text) { super(android.view.AbsSavedState.EMPTY_STATE); this.text = text; }
		private SavedState(android.os.Parcel in) { super(in); text = in.readString(); }
		public static final android.os.Parcelable.Creator<SavedState> CREATOR = new android.os.Parcelable.Creator<SavedState>() {
			public SavedState createFromParcel(android.os.Parcel in) { return new SavedState(in); }
			public SavedState[] newArray(int size) { return new SavedState[size]; }
		};
		@Override
		public int describeContents() { return 0; }
		@Override
		public void writeToParcel(android.os.Parcel dest, int flags) {
			super.writeToParcel(dest, flags);
			dest.writeString(text == null ? "" : text.toString());
		}
	}

	@Override
	public android.os.Parcelable onSaveInstanceState() {
		return new SavedState(text);
	}

	@Override
	protected void onRestoreInstanceState(android.os.Parcelable state) {
		if (state instanceof SavedState) {
			CharSequence restored = ((SavedState)state).text;
			if (restored != null)
				setText(restored);
		}
	}

	private ColorStateList colors = new ColorStateList(new int[][] {new int[0]}, new int[1]);
	private CharSequence text = "";
	private int inputType;
	private int highlightColor = 0x6633b5e5;
	private ColorStateList hintColors = ColorStateList.valueOf(0xff808080);
	private TransformationMethod transformationMethod;
	private TextPaint paint = new TextPaint();
	private int textLayoutWidth = -1;
	private boolean include_padding = false;
	private int break_strategy = 0 /*BREAK_STRATEGY_SIMPLE*/;
	private int hyphenation_frequency = 0 /*HYPHENATION_FREQUENCY_NONE*/;
	private int gravity = Gravity.CENTER;

	public TextView(Context context, AttributeSet attrs) {
		this(context, attrs, com.android.internal.R.attr.textViewStyle);
	}

	public TextView(Context context) {
		this(context, null);
	}

	public TextView(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public TextView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		// AOSP TextView starts with 15px, then applies appearance and direct style.
		setRawTextSize(15);
		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.TextView, defStyleAttr, defStyleRes);
		try {
			setHighlightColor(a.getColor(com.android.internal.R.styleable.TextView_textColorHighlight, highlightColor));
			ColorStateList hintStyle = a.getColorStateList(com.android.internal.R.styleable.TextView_textColorHint);
			setHintTextColor(hintStyle == null ? hintColors : hintStyle);
			if (a.hasValue(com.android.internal.R.styleable.TextView_text)) {
				setText(a.getText(com.android.internal.R.styleable.TextView_text));
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_hint)) {
				setHint(a.getText(com.android.internal.R.styleable.TextView_hint));
			}

			int ap = a.getResourceId(com.android.internal.R.styleable.TextView_textAppearance, -1);
			if (ap != -1) {
				TypedArray aa = context.obtainStyledAttributes(ap, com.android.internal.R.styleable.TextAppearance);
				if (aa.hasValue(com.android.internal.R.styleable.TextAppearance_textColor)) {
					setTextColor(aa.getColorStateList(com.android.internal.R.styleable.TextAppearance_textColor));
				}
				if (aa.hasValue(com.android.internal.R.styleable.TextAppearance_textSize)) {
					setRawTextSize(aa.getDimensionPixelSize(com.android.internal.R.styleable.TextAppearance_textSize, 15));
				}
				aa.recycle();
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_textColor)) {
				setTextColor(a.getColorStateList(com.android.internal.R.styleable.TextView_textColor));
			}
			if (a.hasValue(com.android.internal.R.styleable.TextView_textSize)) {
				setRawTextSize(a.getDimensionPixelSize(com.android.internal.R.styleable.TextView_textSize, 15));
			}

			if (a.hasValue(com.android.internal.R.styleable.TextView_textStyle)) {
				int textStyle = a.getInt(com.android.internal.R.styleable.TextView_textStyle, 0);
				setTypeface(getTypeface(), textStyle);
			}

			setLineSpacing(a.getDimensionPixelSize(com.android.internal.R.styleable.TextView_lineSpacingExtra, 0),
			    a.getFloat(com.android.internal.R.styleable.TextView_lineSpacingMultiplier, 1f));

			if (a.hasValue(com.android.internal.R.styleable.TextView_textAllCaps)) {
				boolean allCaps = a.getBoolean(com.android.internal.R.styleable.TextView_textAllCaps, false);
				setAllCaps(allCaps);
			}
		} catch (java.lang.Exception e) {
			System.out.println("exception while inflating TextView:");
			e.printStackTrace();
		}

		a.recycle();
		haveCustomMeasure = false;
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);

	public void setText(int resId) {
		setText(getContext().getResources().getText(resId));
	}

	public void setText(CharSequence text) {
		setText(text, BufferType.NORMAL);
	}

	public void setText(CharSequence text, BufferType type) {
		this.text = text == null ? "" : text;
		native_setText(text != null ? text.toString() : null);

		int[] spans = android.atl.TextSpanAttributes.encode(this.text, getResources().getDisplayMetrics().density);
		if (text instanceof android.text.Spanned && spans.length == 0)
			native_set_markup(1);
		native_setTextAttributes(spans);
		if (!isLayoutRequested())
			requestLayout();
	}

	public void setText(char[] text, int start, int len) {
		setText(new String(text, start, len));
	}

	public final void append(CharSequence text) {
		append(text, 0, text.length());
	}

	public void append(CharSequence text, int start, int end) {
		setText(SpannableStringBuilder.valueOf(this.text).append(text, start, end));
	}

	private native final void native_set_markup(int bool);

	public native final void native_setText(String text);
	private native void native_setTextAttributes(int[] attributes);

	public void setTextSize(int unit, float size) {
		setRawTextSize(TypedValue.applyDimension(unit, size, getResources().getDisplayMetrics()));
	}

	// Like AOSP TextView (250714c6), construction must not dispatch to a
	// subclass setter before that subclass has initialized its text helpers.
	private void setRawTextSize(float pixels) {
		paint.setTextSize(pixels);
		native_setTextSize(pixels);
		requestLayout();
		invalidate();
	}
	public void setTextSize(float size) { setTextSize(TypedValue.COMPLEX_UNIT_SP, size); }
	private native void native_setTextSize(float pixels);

	public native final void native_setTextColor(int color);
	public void setTextColor(int color) {
		native_setTextColor(color);
	}
	public void setTextColor(ColorStateList colors) {
		if (colors != null) {
			this.colors = colors;
			native_setTextColor(colors.getDefaultColor()); // TODO: do this properly
		}
	}
	public void setTypeface(Typeface tf, int style) { setTypeface(Typeface.create(tf, style)); }
	public void setTypeface(Typeface tf) {
		paint.setTypeface(tf);
		native_setTypeface(widget, (tf == null ? Typeface.DEFAULT : tf).native_instance);
	}
	private native void native_setTypeface(long widget, long typeface);
	private float lineSpacingExtra = 0, lineSpacingMultiplier = 1;
	public void setLineSpacing(float add, float mult) {
		if (Float.isNaN(add) || Float.isInfinite(add) || Float.isNaN(mult) || Float.isInfinite(mult))
			throw new IllegalArgumentException("Line spacing must be finite");
		if (lineSpacingExtra == add && lineSpacingMultiplier == mult) return;
		native_setLineSpacing(widget, add, mult);
		lineSpacingExtra = add;
		lineSpacingMultiplier = mult;
		requestLayout();
		invalidate();
	}
	private native void native_setLineSpacing(long widget, float add, float mult);
	public final void setLinksClickable(boolean whether) {}

	public void setInputType(int type) {
		inputType = type;
		int klass = type & 0xf, variation = type & 0xff0;
		boolean password = (klass == 1 && (variation == 0x80 || variation == 0xe0))
		                || (klass == 2 && variation == 0x10);
		setTransformationMethod(password ? android.text.method.PasswordTransformationMethod.getInstance() : null);
	}
	public void setFilters(InputFilter[] filters) {}
	public void setCursorVisible(boolean visible) {}
	public void setImeOptions(int imeOptions) {}

	public final ColorStateList getTextColors() { return colors; }
	public static ColorStateList getTextColors(Context context, TypedArray attrs) { return new ColorStateList(new int[][] {new int[0]}, new int[1]); }

	public TextPaint getPaint() {
		return paint;
	}

	public void addTextChangedListener(TextWatcher watcher) {}
	public void removeTextChangedListener(TextWatcher watcher) {}
	public void setOnEditorActionListener(TextView.OnEditorActionListener l) {}

	public TransformationMethod getTransformationMethod() {
		return transformationMethod;
	}

	public void setHintTextColor(ColorStateList colors) {
		hintColors = java.util.Objects.requireNonNull(colors);
		native_setHintTextColor(widget, colors.getDefaultColor());
	}
	public void setHintTextColor(int color) { setHintTextColor(ColorStateList.valueOf(color)); }
	public ColorStateList getHintTextColors() { return hintColors; }
	public int getCurrentHintTextColor() { return hintColors.getDefaultColor(); }
	private native void native_setHintTextColor(long widget, int color);
	public void setLinkTextColor(ColorStateList colorStateList) {}

	public void setSingleLine() {}
	public void setSelection(int i) {}
	public void setSelection(int i, int j) {}

	public int getSelectionStart() {
		return 0;
	}

	public int getSelectionEnd() {
		return 0;
	}

	public void setEllipsize(TextUtils.TruncateAt truncateAt) {}

	public void setTextAppearance(Context context, int appearance) {}

	public void setMaxLines(int maxLines) {}

	public void setMinWidth(int minWidth) {}
	public void setMaxWidth(int maxWidth) {}

	public Typeface getTypeface() { return paint.getTypeface(); }

	public float getTextSize() { return paint.getTextSize(); }

	public int getGravity() {
		return gravity;
	}

	public int getCompoundPaddingTop() { return 0; }
	public int getCompoundPaddingBottom() { return 0; }

	public CharSequence getText() {
		return text;
	};

	public void setCompoundDrawablePadding(int pad) {}

	protected native void native_setCompoundDrawables(long widget, long left, long top, long right, long bottom);

	// just to prevent garbage collection while native side uses it
	private Drawable drawableLeft = null;
	private Drawable drawableTop = null;
	private Drawable drawableRight = null;
	private Drawable drawableBottom = null;

	public void setCompoundDrawables(Drawable left, Drawable top, Drawable right, Drawable bottom) {
		native_setCompoundDrawables(widget,
		                            left != null ? left.paintable : 0,
		                            top != null ? top.paintable : 0,
		                            right != null ? right.paintable : 0,
		                            bottom != null ? bottom.paintable : 0);
		drawableLeft = left;
		drawableTop = top;
		drawableRight = right;
		drawableBottom = bottom;
	}

	public void setAllCaps(boolean allCaps) {
		String[] classesToRemove = {"ATL-text-uppercase"};
		native_removeClasses(widget, classesToRemove);

		if (allCaps) {
			native_addClass(widget, "ATL-text-uppercase");
		}
	}

	public void setSaveEnabled(boolean enabled) { super.setSaveEnabled(enabled); }

	public final void setAutoLinkMask(int mask) {}

	public void setEditableFactory(Editable.Factory factory) {}

	public KeyListener getKeyListener() { return null; }

	private String fontFeatureSettings;

	public String getFontFeatureSettings() { return fontFeatureSettings; }

	public void setFontFeatureSettings(String settings) {
		native_setFontFeatureSettings(widget, settings);
		fontFeatureSettings = settings;
	}

	private native void native_setFontFeatureSettings(long widget, String settings);

	public int getInputType() { return inputType; }

	public final void setTransformationMethod(TransformationMethod method) {
		transformationMethod = method;
		native_setPasswordVisibility(widget, !(method instanceof android.text.method.PasswordTransformationMethod));
	}
	private native void native_setPasswordVisibility(long widget, boolean visible);

	public InputFilter[] getFilters() { return new InputFilter[0]; }

	public int getMaxLines() { return -1; }

	public void setCompoundDrawablesRelative(Drawable start, Drawable top, Drawable end, Drawable bottom) {
		setCompoundDrawables(start, top, end, bottom);
	}

	public int getLineCount() {
		Layout layout = getLayout();
		return layout == null ? 0 : layout.getLineCount();
	}

	public URLSpan[] getUrls() { return new URLSpan[0]; }

	public void setMovementMethod(MovementMethod method) {}

	public void setTextIsSelectable(boolean selectable) {}

	public MovementMethod getMovementMethod() {
		return new BaseMovementMethod();
	}

	public CharSequence getHint() { return "HINT"; }

	public int getMinHeight() { return 0; }
	public int getMinWidth() { return 0; }
	public void setMinHeight(int minHeight) {}
	public void setMaxHeight(int maxHeight) {}

	public void setHorizontallyScrolling(boolean whether) {}
	public boolean getHorizontallyScrolling() {
		return false;
	}

	public static interface OnEditorActionListener {
		public abstract boolean onEditorAction(TextView v, int actionId, KeyEvent event);
	}

	public static enum BufferType {
		EDITABLE,
		NORMAL,
		SPANNABLE,
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		// Parents inspect text lines during measurement, before View.layout has
		// assigned a frame. The frame width may be zero or from an earlier pass.
		textLayoutWidth = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
	}

	public Layout getLayout() {
		if (textLayoutWidth < 0) return null;
		return new BoringLayout(getText(), getPaint(), textLayoutWidth, Layout.Alignment.ALIGN_NORMAL, lineSpacingMultiplier, lineSpacingExtra, new BoringLayout.Metrics(), false);
	}

	public int getCurrentTextColor() {
		return Color.CYAN;
	}

	public void setSingleLine(boolean singleLine) {}

	public int getCompoundPaddingLeft() { return 0; }

	public int getCompoundPaddingRight() { return 0; }

	public void setHint(int resId) {
		setHint(getContext().getResources().getText(resId));
	}

	public float getLetterSpacing() { return 0.f; }

	public void setCompoundDrawablesRelativeWithIntrinsicBounds(int start, int top, int end, int bottom) {}

	public void setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable start, Drawable top, Drawable end, Drawable bottom) {}

	public boolean getLinksClickable() { return true; }

	public boolean isTextSelectable() { return true; }

	public void setCompoundDrawablesWithIntrinsicBounds(int left, int top, int right, int bottom) {}

	public void setCompoundDrawablesWithIntrinsicBounds(Drawable left, Drawable top, Drawable right, Drawable bottom) {}

	public void setHint(CharSequence s) {}

	public Drawable[] getCompoundDrawablesRelative() { return new Drawable[4]; }

	public Drawable[] getCompoundDrawables() { return new Drawable[4]; }

	public void setTextAppearance(int dummy) {}

	public int length() {
		return getText().length();
	}

	public void setHighlightColor(int color) {
		highlightColor = color;
		native_setHighlightColor(widget, color);
	}
	public int getHighlightColor() { return highlightColor; }
	private native void native_setHighlightColor(long widget, int color);

	public Editable getEditableText() {
		return new SpannableStringBuilder(getText());
	}

	public int getMaxWidth() { return 1000; }

	public void nullLayouts() {}

	public void setLinkTextColor(int color) {}

	public void setCustomSelectionActionModeCallback(ActionMode.Callback actionModeCallback) {}

	public int getExtendedPaddingTop() { return 0; }

	public void setRawInputType(int type) { inputType = type; }

	public TextUtils.TruncateAt getEllipsize() { return null; }

	public void setLines(int lines) {}

	public int getMinLines() {
		return -1;
	}

	public void setMinLines(int lines) {}

	public void setSelectAllOnFocus(boolean selectAllOnFocus) {}

	public int getCompoundDrawablePadding() { return 0; }

	public int getPaintFlags() { return 0; }

	public void setPaintFlags(int flags) {}

	public int getLineHeight() {
		return paint.getFontMetricsInt(null);
	}

	public int getMaxHeight() {
		return -1;
	}

	public boolean isAllCaps() { return false; }

	public int getAutoSizeStepGranularity() {
		return -1;
	}

	public void setAutoSizeTextTypeUniformWithPresetSizes(int[] presetSizes, int unit) {}

	public void setCompoundDrawableTintList(ColorStateList tint) {}

	public int getHyphenationFrequency() {
		return hyphenation_frequency;
	}

	public void setHyphenationFrequency(int hyphenationFrequency) {
		hyphenation_frequency = hyphenationFrequency;
	}

	public boolean getIncludeFontPadding() { return include_padding; }

	public void setIncludeFontPadding(boolean includePadding) {
		include_padding = includePadding;
	}

	public float getLineSpacingExtra() { return lineSpacingExtra; }

	public float getLineSpacingMultiplier() { return lineSpacingMultiplier; }

	public TextDirectionHeuristic getTextDirectionHeuristic() {
		return TextDirectionHeuristics.LTR;
	}

	public Bundle getInputExtras(boolean key) {
		return new Bundle();
	}

	public void setError(CharSequence error) {
		System.out.println("ERROR: " + error);
	}

	public int getTotalPaddingLeft() { return 0; }

	public int getTotalPaddingTop() { return 0; }

	public int getTotalPaddingRight() { return 0; }

	public int getTotalPaddingBottom() { return 0; }

	public int getImeOptions() { return 0; }

	private float shadowRadius, shadowDx, shadowDy;
	private int shadowColor;
	public void setShadowLayer(float radius, float dx, float dy, int color) {
		if (!Float.isFinite(radius) || !Float.isFinite(dx) || !Float.isFinite(dy))
			throw new IllegalArgumentException("Shadow dimensions must be finite");
		native_setShadowLayer(widget, radius, dx, dy, color);
		shadowRadius = radius; shadowDx = dx; shadowDy = dy; shadowColor = color;
	}
	public float getShadowRadius() { return shadowRadius; }
	public float getShadowDx() { return shadowDx; }
	public float getShadowDy() { return shadowDy; }
	public int getShadowColor() { return shadowColor; }
	private native void native_setShadowLayer(long widget, float radius, float dx, float dy, int color);

	public int getBreakStrategy() {
		return break_strategy;
	}

	public void setBreakStrategy(int strategy) {
		break_strategy = strategy;
	}

	public void clearComposingText() {}

	public void setKeyListener(KeyListener keyListener) {}

	public int getAutoLinkMask() { return 0; }

	public void setWidth(int width) {}
	public void setHeight(int height) {}

	public void setFreezesText(boolean freezesText) {}

	public void setLetterSpacing(float letterSpacing) {}

	public void setMarqueeRepeatLimit(int marqueeLimit) {}

	private boolean showSoftInputOnFocus = true;
	public boolean getShowSoftInputOnFocus() { return showSoftInputOnFocus; }
	public void setShowSoftInputOnFocus(boolean show) {
		showSoftInputOnFocus = show;
		native_setShowSoftInputOnFocus(widget, show);
	}
	private native void native_setShowSoftInputOnFocus(long widget, boolean show);

	public void setCustomInsertionActionModeCallback(ActionMode.Callback actionModeCallback) {}

	public int getJustificationMode() {
		return 0; // JUSTIFICATION_MODE_NONE
	}

	public void setImeActionLabel(CharSequence label, int actionId) {}

	public int getExtendedPaddingBottom() { return 0; }

	public boolean hasSelection() { return false; }

	@Override
	public void setTextAlignment(int textAlignment) {
		super.setTextAlignment(textAlignment);
		native_setTextAlignment(widget, getLayoutAlignment().ordinal());
	}
	private native void native_setTextAlignment(long widget, int alignment);

	@UnsupportedAppUsage /* androidx ACTVAutoSizeHelper seems to love this */
	/* Copyright (C) 2006 The Android Open Source Project */
	private Layout.Alignment getLayoutAlignment() {
		Layout.Alignment alignment;
		switch (getTextAlignment()) {
			case TEXT_ALIGNMENT_GRAVITY:
				switch (gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
					case Gravity.START:
						alignment = Layout.Alignment.ALIGN_NORMAL;
						break;
					case Gravity.END:
						alignment = Layout.Alignment.ALIGN_OPPOSITE;
						break;
					case Gravity.LEFT:
						alignment = Layout.Alignment.ALIGN_LEFT;
						break;
					case Gravity.RIGHT:
						alignment = Layout.Alignment.ALIGN_RIGHT;
						break;
					case Gravity.CENTER_HORIZONTAL:
						alignment = Layout.Alignment.ALIGN_CENTER;
						break;
					default:
						alignment = Layout.Alignment.ALIGN_NORMAL;
						break;
				}
				break;
			case TEXT_ALIGNMENT_TEXT_START:
				alignment = Layout.Alignment.ALIGN_NORMAL;
				break;
			case TEXT_ALIGNMENT_TEXT_END:
				alignment = Layout.Alignment.ALIGN_OPPOSITE;
				break;
			case TEXT_ALIGNMENT_CENTER:
				alignment = Layout.Alignment.ALIGN_CENTER;
				break;
			case TEXT_ALIGNMENT_VIEW_START:
				alignment = (getLayoutDirection() == LAYOUT_DIRECTION_RTL) ? Layout.Alignment.ALIGN_RIGHT : Layout.Alignment.ALIGN_LEFT;
				break;
			case TEXT_ALIGNMENT_VIEW_END:
				alignment = (getLayoutDirection() == LAYOUT_DIRECTION_RTL) ? Layout.Alignment.ALIGN_LEFT : Layout.Alignment.ALIGN_RIGHT;
				break;
			case TEXT_ALIGNMENT_INHERIT:
				// This should never happen as we have already resolved the text alignment
				// but better safe than sorry so we just fall through
			default:
				alignment = Layout.Alignment.ALIGN_NORMAL;
				break;
		}
		return alignment;
	}
}
