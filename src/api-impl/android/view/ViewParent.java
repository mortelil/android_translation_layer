package android.view;

public interface ViewParent {
	boolean getChildVisibleRect(View child, android.graphics.Rect rect, android.graphics.Point offset);
	public abstract ViewParent getParent();

	/** Moves a child to the top of its parent's drawing order. */
	public void bringChildToFront(View child);

	public boolean isLayoutRequested();

	public void requestDisallowInterceptTouchEvent(boolean disallowIntercept);

	public abstract boolean onStartNestedScroll(View child, View target, int nestedScrollAxes);

	public boolean onNestedPreFling(View target, float velocityX, float velocityY);

	public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed);

	public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes);

	public void onNestedPreScroll(View target, int dx, int dy, int[] consumed);

	public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed);

	public void onStopNestedScroll(View target);

	public void onDescendantInvalidated(View child, View target);

	public void requestLayout();
}
