/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.View;

public class ImageView extends View {

	private ScaleType scaleType = ScaleType.FIT_CENTER;
	private Drawable drawable = null;
	private ColorFilter colorFilter = null;

	public ImageView(Context context) {
		this(context, null);
	}

	public ImageView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public ImageView(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public ImageView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		/* ImageView sizes itself in onMeasure() (AOSP ImageView.onMeasure) instead of
		   letting View.onMeasure hand the job to the GTK widget's native_measure. */
		haveCustomMeasure = true;
		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.ImageView, defStyleAttr, defStyleRes);
		if (a.hasValue(com.android.internal.R.styleable.ImageView_tint))
			colorFilter = new PorterDuffColorFilter(a.getColor(com.android.internal.R.styleable.ImageView_tint, 0),
			                                        PorterDuff.Mode.values()[a.getInt(com.android.internal.R.styleable.ImageView_tintMode, PorterDuff.Mode.SRC_IN.nativeInt)]);
		drawable = a.getDrawable(com.android.internal.R.styleable.ImageView_src);
		if (drawable != null) {
			drawable.setCallback(this);
			if (drawable.isStateful()) drawable.setState(getDrawableState());
			if (colorFilter != null)
				drawable.setColorFilter(colorFilter);
			native_setDrawable(widget, drawable.paintable);
		}
		if (a.hasValue(com.android.internal.R.styleable.ImageView_scaleType))
			setScaleType(scaletype_from_int[a.getInt(com.android.internal.R.styleable.ImageView_scaleType, 3 /*CENTER*/)]);
		maxWidth = a.getDimensionPixelSize(com.android.internal.R.styleable.ImageView_maxWidth, Integer.MAX_VALUE);
		maxHeight = a.getDimensionPixelSize(com.android.internal.R.styleable.ImageView_maxHeight, Integer.MAX_VALUE);
		setAdjustViewBounds(a.getBoolean(com.android.internal.R.styleable.ImageView_adjustViewBounds, false));
		a.recycle();
	}

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (drawable != null && drawable.isStateful() && drawable.setState(getDrawableState())) invalidate();
    }

	public void setImageResource(final int resid) {
		setImageDrawable(resid == 0 ? null : getContext().getDrawable(resid));
	}
	public void setAdjustViewBounds(boolean adjustViewBounds) {
		this.adjustViewBounds = adjustViewBounds;
		if (adjustViewBounds) setScaleType(ScaleType.FIT_CENTER);
		requestLayout();
	}

	/* AOSP android/widget/ImageView.java onMeasure()/resolveAdjustedSize()
	 * (SDK sources android-37.0, ImageView.java:1129-1273; the pinned reference
	 * tree carries only core/java, so android/widget is not there).
	 *
	 * Without this override ATL delegated ImageView sizing to the GTK
	 * GtkPicture of the view (View.onMeasure's haveCustomMeasure == false
	 * path -> native_measure): GtkPicture answers GTK's *height-for-width*
	 * with the aspect-preserving natural height, so a 40x40 drawable in a
	 * wrap_content ImageView measured 438x438 (the full available width) and
	 * pushed the rest of the layout out of its window. AOSP measures
	 * wrap_content from the drawable's intrinsic size instead; only
	 * adjustViewBounds preserves the aspect ratio. */
	private boolean adjustViewBounds = false;
	private int maxWidth = Integer.MAX_VALUE;
	private int maxHeight = Integer.MAX_VALUE;

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int w;
		int h;

		// Desired aspect ratio of the view's contents (not including padding)
		float desiredAspect = 0.0f;

		// We are allowed to change the view's width
		boolean resizeWidth = false;

		// We are allowed to change the view's height
		boolean resizeHeight = false;

		final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
		final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);

		if (drawable == null) {
			// If no drawable, its intrinsic size is 0.
			w = h = 0;
		} else {
			w = drawable.getIntrinsicWidth();
			h = drawable.getIntrinsicHeight();
			if (w <= 0) w = 1;
			if (h <= 0) h = 1;

			// We are supposed to adjust view bounds to match the aspect
			// ratio of our drawable. See if that is possible.
			if (adjustViewBounds) {
				resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
				resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;

				desiredAspect = (float) w / (float) h;
			}
		}

		final int pleft = getPaddingLeft();
		final int pright = getPaddingRight();
		final int ptop = getPaddingTop();
		final int pbottom = getPaddingBottom();

		int widthSize;
		int heightSize;

		if (resizeWidth || resizeHeight) {
			// Get the max possible width given our constraints
			widthSize = resolveAdjustedSize(w + pleft + pright, maxWidth, widthMeasureSpec);

			// Get the max possible height given our constraints
			heightSize = resolveAdjustedSize(h + ptop + pbottom, maxHeight, heightMeasureSpec);

			if (desiredAspect != 0.0f) {
				// See what our actual aspect ratio is
				final float actualAspect = (float) (widthSize - pleft - pright)
				                          / (heightSize - ptop - pbottom);

				if (Math.abs(actualAspect - desiredAspect) > 0.0000001) {
					boolean done = false;

					// Try adjusting width to be proportional to height
					if (resizeWidth) {
						int newWidth = (int) (desiredAspect * (heightSize - ptop - pbottom)) + pleft + pright;

						// Allow the width to outgrow its original estimate if height is fixed.
						if (!resizeHeight)
							widthSize = resolveAdjustedSize(newWidth, maxWidth, widthMeasureSpec);

						if (newWidth <= widthSize) {
							widthSize = newWidth;
							done = true;
						}
					}

					// Try adjusting height to be proportional to width
					if (!done && resizeHeight) {
						int newHeight = (int) ((widthSize - pleft - pright) / desiredAspect) + ptop + pbottom;

						// Allow the height to outgrow its original estimate if width is fixed.
						if (!resizeWidth)
							heightSize = resolveAdjustedSize(newHeight, maxHeight, heightMeasureSpec);

						if (newHeight <= heightSize)
							heightSize = newHeight;
					}
				}
			}
		} else {
			/* We are either don't want to preserve the drawables aspect ratio,
			   or we are not allowed to change view dimensions. Just measure in
			   the normal way. */
			w += pleft + pright;
			h += ptop + pbottom;

			w = Math.max(w, getSuggestedMinimumWidth());
			h = Math.max(h, getSuggestedMinimumHeight());

			widthSize = resolveSizeAndState(w, widthMeasureSpec, 0);
			heightSize = resolveSizeAndState(h, heightMeasureSpec, 0);
		}

		setMeasuredDimension(widthSize, heightSize);
	}

	private int resolveAdjustedSize(int desiredSize, int maxSize, int measureSpec) {
		int result = desiredSize;
		final int specMode = MeasureSpec.getMode(measureSpec);
		final int specSize = MeasureSpec.getSize(measureSpec);
		switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				/* Parent says we can be as big as we want. Just don't be larger
				   than max size imposed on ourselves. */
				result = Math.min(desiredSize, maxSize);
				break;
			case MeasureSpec.AT_MOST:
				/* Parent says we can be as big as we want, up to specSize.
				   Don't be larger than specSize, and don't be larger than
				   the max size imposed on ourselves. */
				result = Math.min(Math.min(desiredSize, specSize), maxSize);
				break;
			case MeasureSpec.EXACTLY:
				// No choice. Do what we are told.
				result = specSize;
				break;
		}
		return result;
	}

	public boolean getAdjustViewBounds() { return adjustViewBounds; }
	public int getMaxWidth() { return maxWidth; }
	public int getMaxHeight() { return maxHeight; }

	public void setScaleType(ScaleType scaleType) {
		this.scaleType = scaleType;
		native_setScaleType(widget, scaleType.nativeInt);
	}

	public ScaleType getScaleType() {
		return scaleType;
	}

	public Drawable getDrawable() {
		return drawable;
	}

	public void setImageDrawable(Drawable drawable) {
		if (drawable != null && colorFilter != null) {
			drawable = drawable.mutate();
			drawable.setColorFilter(colorFilter);
		}
		if (this.drawable != null) {
			this.drawable.setCallback(null);
		}
		this.drawable = drawable;
		if (drawable != null) {
			drawable.setCallback(this);
			if (drawable.isStateful()) drawable.setState(getDrawableState());
		}
		native_setDrawable(widget, drawable != null ? drawable.paintable : 0);
		requestLayout();
		invalidate();
	}

	public void setImageMatrix(Matrix matrix) {}

	public void setImageBitmap(Bitmap bitmap) {
		setImageDrawable(bitmap == null ? null : new BitmapDrawable(getResources(), bitmap));
	}

	/**
	 * Options for scaling the bounds of an image to the bounds of this view.
	 */
	public enum ScaleType {
		/**
		 * Scale using the image matrix when drawing. The image matrix can be set using
		 * {@link ImageView#setImageMatrix(Matrix)}. From XML, use this syntax:
		 * <code>android:scaleType="matrix"</code>.
		 */
		MATRIX(0),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#FILL}.
		 * From XML, use this syntax: <code>android:scaleType="fitXY"</code>.
		 */
		FIT_XY(1),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#START}.
		 * From XML, use this syntax: <code>android:scaleType="fitStart"</code>.
		 */
		FIT_START(2),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#CENTER}.
		 * From XML, use this syntax:
		 * <code>android:scaleType="fitCenter"</code>.
		 */
		FIT_CENTER(3),
		/**
		 * Scale the image using {@link Matrix.ScaleToFit#END}.
		 * From XML, use this syntax: <code>android:scaleType="fitEnd"</code>.
		 */
		FIT_END(4),
		/**
		 * Center the image in the view, but perform no scaling.
		 * From XML, use this syntax: <code>android:scaleType="center"</code>.
		 */
		CENTER(5),
		/**
		 * Scale the image uniformly (maintain the image's aspect ratio) so
		 * that both dimensions (width and height) of the image will be equal
		 * to or larger than the corresponding dimension of the view
		 * (minus padding). The image is then centered in the view.
		 * From XML, use this syntax: <code>android:scaleType="centerCrop"</code>.
		 */
		CENTER_CROP(6),
		/**
		 * Scale the image uniformly (maintain the image's aspect ratio) so
		 * that both dimensions (width and height) of the image will be equal
		 * to or less than the corresponding dimension of the view
		 * (minus padding). The image is then centered in the view.
		 * From XML, use this syntax: <code>android:scaleType="centerInside"</code>.
		 */
		CENTER_INSIDE(7);

		ScaleType(int ni) {
			nativeInt = ni;
		}
		final int nativeInt;
	}
	private final ScaleType[] scaletype_from_int = {
		ScaleType.MATRIX,
		ScaleType.FIT_XY,
		ScaleType.FIT_START,
		ScaleType.FIT_CENTER,
		ScaleType.FIT_END,
		ScaleType.CENTER,
		ScaleType.CENTER_CROP,
		ScaleType.CENTER_INSIDE,
	};

	public final void setColorFilter(int color, PorterDuff.Mode mode) {}

	public void setImageTintList(ColorStateList tint) {
		if (tint == null)
			colorFilter = null;
		else
			colorFilter = new PorterDuffColorFilter(tint.getDefaultColor(), PorterDuff.Mode.SRC_IN);
		setImageDrawable(drawable);
	}

	public void setImageAlpha(int alpha) {}

	public void setMaxWidth(int width) {
		maxWidth = width;
		requestLayout();
	}

	public void setMaxHeight(int height) {
		maxHeight = height;
		requestLayout();
	}

	public void setImageState(int[] state, boolean merge) {}

	public ColorStateList getImageTintList() {
		return null;
	}

	public PorterDuff.Mode getImageTintMode() {
		return null;
	}

	public void setImageTintMode(PorterDuff.Mode tintMode) {}

	public void setCropToPadding(boolean crop) {}

	public void setColorFilter(int color) {}

	public void setColorFilter(ColorFilter cf) {}

	public Matrix getImageMatrix() {
		return Matrix.IDENTITY_MATRIX;
	}

	public void clearColorFilter() {
		colorFilter = null;
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	protected native void native_setDrawable(long widget, long paintable);
	protected native void native_setScaleType(long widget, int scale_type);
}
