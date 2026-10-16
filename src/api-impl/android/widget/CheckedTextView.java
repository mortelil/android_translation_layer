package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;

public class CheckedTextView extends TextView implements Checkable {
	private boolean checked;
	private Drawable checkMark;

	public CheckedTextView(Context context) { super(context); }
	public CheckedTextView(Context context, AttributeSet attrs) { super(context, attrs); }

	@Override
	public void setChecked(boolean checked) {
		if (this.checked == checked) return;
		this.checked = checked;
		refreshDrawableState();
	}

	@Override
	public boolean isChecked() { return checked; }
	public void toggle() { setChecked(!checked); }

	@Override
	protected int[] onCreateDrawableState(int extraSpace) {
		int[] states = super.onCreateDrawableState(extraSpace + 1);
		if (checked) mergeDrawableStates(states, new int[]{android.R.attr.state_checked});
		return states;
	}

	public void setCheckMarkDrawable(int resource) {
		setCheckMarkDrawable(resource == 0 ? null : getContext().getDrawable(resource));
	}

	public void setCheckMarkDrawable(Drawable drawable) {
		if (checkMark != null) checkMark.setCallback(null);
		checkMark = drawable;
		if (drawable != null) {
			drawable.setCallback(this);
			drawable.setState(getDrawableState());
		}
		updateMarkPadding();
		requestLayout();
		invalidate();
	}

	public Drawable getCheckMarkDrawable() { return checkMark; }

	@Override
	protected void drawableStateChanged() {
		super.drawableStateChanged();
		if (checkMark != null && checkMark.setState(getDrawableState())) invalidate();
	}

	private int markWidth() { return checkMark == null ? 0 : Math.max(0, checkMark.getIntrinsicWidth()); }
	private void updateMarkPadding() {
		int width = markWidth();
		native_setPadding(widget, getPaddingLeft() + (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? width : 0),
			getPaddingTop(), getPaddingRight() + (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 0 : width), getPaddingBottom());
	}

	@Override
	public void setPadding(int left, int top, int right, int bottom) {
		super.setPadding(left, top, right, bottom);
		updateMarkPadding();
	}

	@Override
	public void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (checkMark == null) return;
		int width = markWidth(), height = Math.max(0, checkMark.getIntrinsicHeight());
		int x = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getPaddingLeft() : getWidth() - getPaddingRight() - width;
		int gravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
		int y = gravity == Gravity.BOTTOM ? getHeight() - getPaddingBottom() - height :
			gravity == Gravity.CENTER_VERTICAL ? (getHeight() - height) / 2 : getPaddingTop();
		checkMark.setBounds(x, y, x + width, y + height);
		checkMark.draw(canvas);
	}
}
