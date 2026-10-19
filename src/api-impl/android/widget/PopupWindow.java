package android.widget;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

public class PopupWindow {

	int input_method_mode = 0;
	private boolean attachedInDecor = true;
	private boolean attachedInDecorSet;
	private boolean overlapAnchor;
	private int dropDownGravity = Gravity.START;
	private int requestedWidth;
	private int requestedHeight;

	public PopupWindow(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		popover = native_constructor();
		attachedInDecor = context.getApplicationInfo().targetSdkVersion >= 22;
	}

	public PopupWindow(Context context) {
		this(context, null, 0, 0);
	}

	public PopupWindow() {
		popover = native_constructor();
	}

	// Matches AOSP android-15.0.0_r1 PopupWindow(View): zero size, no focus.
	public PopupWindow(View contentView) {
		this(contentView, 0, 0, false);
	}

	public PopupWindow(View contentView, int width, int height, boolean focusable) {
		popover = native_constructor();
		setContentView(contentView);
		setWidth(width);
		setHeight(height);
		setFocusable(focusable);
	}

	public PopupWindow(View contentView, int width, int height) {
		this(contentView, width, height, true);
	}

	private View contentView;
	private FrameLayout decorView;
	private Drawable background;
	private long popover; // native pointer to GtkPopover

	public interface OnDismissListener {
		public void onDismiss();
	}

	public void setBackgroundDrawable(Drawable background) {
		this.background = background;
		if (decorView != null) {
			decorView.setBackgroundDrawable(background);
		}
	}

	public void setInputMethodMode(int mode) {
		input_method_mode = mode;
	}

	public int getInputMethodMode() {
		return input_method_mode;
	}

	public boolean isShowing() {
		return native_isShowing(popover);
	}

	public void setFocusable(boolean focusable) {}

	public Drawable getBackground() {
		return background;
	}

	public void setContentView(View view) {
		if (contentView == view) return;
		if (decorView != null) decorView.removeAllViews();
		contentView = view;
		if (view != null && !attachedInDecorSet)
			attachedInDecor = view.getContext().getApplicationInfo().targetSdkVersion >= 22;
		// A popup is a separate view root. A container must measure and lay out
		// even a lone TextView; native allocation alone leaves its Java bounds empty.
		decorView = view == null ? null : new FrameLayout(view.getContext());
		if (decorView != null) {
			decorView.setBackground(background);
			decorView.addView(view, new FrameLayout.LayoutParams(-1, -1));
		}
		native_setContentView(popover, decorView == null ? 0 : decorView.widget);
	}

	public int getMaxAvailableHeight(View anchor, int yOffset) {
		return getMaxAvailableHeight(anchor, yOffset, false);
	}

	public int getMaxAvailableHeight(View anchor, int yOffset, boolean ignoreKeyboard) {
		Rect frame = new Rect();
		anchor.getWindowVisibleDisplayFrame(frame);
		int[] location = new int[2];
		anchor.getLocationOnScreen(location);
		int below = frame.bottom - location[1] - (overlapAnchor ? 0 : anchor.getHeight()) - yOffset;
		int above = location[1] - frame.top + yOffset;
		Rect padding = new Rect();
		if (background != null) background.getPadding(padding);
		return Math.max(0, Math.max(above, below) - padding.top - padding.bottom);
	}

	public void setOutsideTouchable(boolean touchable) {
		/* FIXME: the semantics are different, this seems to specifically exist for cases
		 * where the popup is *not* modal, so that in addition to the window behind getting
		 * the real event, the popup gets a special MotionEvent.ACTION_OUTSIDE event */
		native_setTouchModal(popover, touchable);
	}

	public void setTouchInterceptor(View.OnTouchListener listener) {}

	public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
		if (!anchor.isAttachedToWindow()) {
			Log.e("PopupWindow", "anchor is not attached to window");
			return;
		}
		dropDownGravity = gravity;
		native_show(popover, anchor.widget, anchor.getRootView().widget, anchor.getWidth(), anchor.getHeight(), xoff, yoff,
			Gravity.getAbsoluteGravity(gravity, anchor.getLayoutDirection()), false,
			attachedInDecor, overlapAnchor);
	}

	public View getContentView() {
		return contentView;
	}

	public void setTouchable(boolean touchable) {
		native_setTouchable(popover, touchable);
	}

	public void showAsDropDown(View anchor) {
		showAsDropDown(anchor, 0, 0);
	}

	public void showAsDropDown(View anchor, int xoff, int yoff) {
		showAsDropDown(anchor, xoff, yoff, Gravity.START);
	}

	public void showAtLocation(View parent, int gravity, int x, int y) {
		View root = parent.getRootView();
		native_show(popover, root.widget, root.widget, root.getWidth(), root.getHeight(), x, y,
			Gravity.getAbsoluteGravity(gravity, parent.getLayoutDirection()), true,
			attachedInDecor, false);
	}

	public void setAttachedInDecor(boolean enabled) {
		attachedInDecor = enabled;
		attachedInDecorSet = true;
	}

	public boolean isAttachedInDecor() { return attachedInDecor; }

	public void dismiss() {
		native_dismiss(popover);
	}

	public void setAnimationStyle(int animationStyle) {}

	public void setTouchModal(boolean touchModal) {
		native_setTouchModal(popover, touchModal);
	}

	public void setElevation(float elevation) {}

	public void update(View anchor, int xoff, int yoff, int width, int height) {
		if (width >= 0) setWidth(width);
		if (height >= 0) setHeight(height);
		showAsDropDown(anchor, xoff, yoff, dropDownGravity);
	}

	public void setWindowLayoutType(int type) {}

	public void setIsClippedToScreen(boolean isClippedToScreen) {}

	public void setEpicenterBounds(Rect bounds) {}

	public void setClippingEnabled(boolean enabled) {}

	/* TODO: handle LayoutParams.WRAP_CONTENT and LayoutParams.MATCH_PARENT */
	public void setWidth(int width) {
		requestedWidth = width;
		if (width < 0)
			return;

		native_setWidth(popover, width);
	}

	public void setHeight(int height) {
		requestedHeight = height;
		if (height < 0)
			return;

		native_setHeight(popover, height);
	}

	public int getWidth() {
		return requestedWidth;
	}

	public int getHeight() {
		return requestedHeight;
	}

	public void update(int x, int y, int width, int height) {}

	public void setWindowLayoutMode(int widthSpec, int heightSpec) {}

	public boolean isTouchable() {
		return native_isTouchable(popover);
	}

	public void setOverlapAnchor(boolean overlap) {
		overlapAnchor = overlap;
	}

	public boolean getOverlapAnchor() { return overlapAnchor; }

	public void setSoftInputMode(int mode) {}

	protected native long native_constructor();
	protected native void native_setContentView(long widget, long contentView);
	protected native void native_show(long widget, long anchor, long frame, int anchorWidth, int anchorHeight, int xoff, int yoff, int gravity, boolean absolute, boolean attached, boolean overlap);
	protected native boolean native_isShowing(long widget);
	protected native void native_setTouchable(long widget, boolean touchable);
	protected native void native_setTouchModal(long widget, boolean touchable);
	protected native void native_dismiss(long widget);
	public native void setOnDismissListener(OnDismissListener listener);
	public native void native_setWidth(long widget, int width);
	public native void native_setHeight(long widget, int height);
	public native int native_getWidth(long widget);
	public native int native_getHeight(long widget);
	public native boolean native_isTouchable(long widget);
}
