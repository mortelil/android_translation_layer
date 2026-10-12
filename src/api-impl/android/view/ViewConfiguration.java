package android.view;

import android.content.Context;

/**
 * default values are mainly based on AOSPs defaults. Does not account for scaling yet.
 */
public class ViewConfiguration {

	/**
	 * Whether this instance was created through {@link #get(Context)} and therefore knows the
	 * resources of a context. Mirrors AOSP's {@code mConstructedWithContext}.
	 */
	private final boolean mConstructedWithContext;

	private final int mMinScalingSpan;

	public ViewConfiguration() {
		mConstructedWithContext = false;
		mMinScalingSpan = 0;
	}

	private ViewConfiguration(Context context) {
		mConstructedWithContext = true;
		// AOSP reads com.android.internal.R.dimen.config_minScalingSpan, which is 27mm
		// (core/res/res/values/config.xml) and is converted to pixels by
		// Resources.getDimensionPixelSize() using xdpi, i.e. value * xdpi / 25.4f rounded.
		mMinScalingSpan = (int) (27f * context.getResources().getDisplayMetrics().xdpi / 25.4f + 0.5f);
	}

	public static ViewConfiguration get(Context context) {
		return new ViewConfiguration(context);
	}

	public int getScaledTouchSlop() {
		return 8;
	}

	public int getScaledMaximumFlingVelocity() {
		return 8000;
	}

	public int getScaledMinimumFlingVelocity() {
		return 50;
	}

	public static int getTapTimeout() {
		return 100;
	}

	public static int getLongPressTimeout() {
		return 400;
	}

	public int getScaledPagingTouchSlop() {
		return 16;
	}

	public boolean hasPermanentMenuKey() {
		return false;
	}

	public static int getDoubleTapTimeout() {
		return 300;
	}

	public int getScaledDoubleTapSlop() {
		return 100;
	}

	public static float getScrollFriction() {
		return 0.015f;
	}

	public int getScaledWindowTouchSlop() {
		return 8;
	}

	public float getScaledHorizontalScrollFactor() {
		return 1;
	}

	public float getScaledVerticalScrollFactor() {
		return 1;
	}

	public static int getScrollBarFadeDuration() {
		return 100;
	}

	public static int getScrollDefaultDelay() {
		return 16;
	}

	public boolean shouldShowMenuShortcutsWhenKeyboardPresent() {
		return true;
	}

	/**
	 * Retrieves the distance in pixels between touches that must be reached for a gesture to be
	 * interpreted as scaling.
	 *
	 * @return The distance in pixels
	 * @throws IllegalStateException if this method is called on a ViewConfiguration that was
	 *         instantiated using a constructor with no Context parameter.
	 */
	public int getScaledMinimumScalingSpan() {
		if (!mConstructedWithContext) {
			throw new IllegalStateException("Min scaling span cannot be determined when this "
					+ "method is called on a ViewConfiguration that was instantiated using a "
					+ "constructor with no Context parameter");
		}
		return mMinScalingSpan;
	}

	public static int getKeyRepeatTimeout() {
		return 1000;
	}

	public static int getKeyRepeatDelay() {
		return 100;
	}
}
