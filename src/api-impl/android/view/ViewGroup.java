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

package android.view;

import android.R;
import android.animation.LayoutTransition;
import android.annotation.UnsupportedAppUsage;
import android.atl.GskCanvas;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.os.Parcelable;
import android.util.Slog;
import android.util.SparseArray;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;

public class ViewGroup extends View implements ViewParent, ViewManager {
	private boolean clipChildren = true;
	private boolean clipToPadding = true;

	// Adapted from AOSP android-14.0.0_r1 ViewGroup.getChildVisibleRect.
	@Override
	public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
		android.graphics.RectF rect = new android.graphics.RectF(r);
		android.graphics.Matrix matrix = child.getMatrix();
		if (!matrix.isIdentity()) matrix.mapRect(rect);
		int dx = child.getLeft() - getScrollX();
		int dy = child.getTop() - getScrollY();
		rect.offset(dx, dy);
		if (offset != null) {
			if (!matrix.isIdentity()) {
				float[] position = {offset.x, offset.y};
				matrix.mapPoints(position);
				offset.set(Math.round(position[0]), Math.round(position[1]));
			}
			offset.offset(dx, dy);
		}
		boolean visible = true;
		int width = getRight() - getLeft(), height = getBottom() - getTop();
		if (parent == null || (parent instanceof ViewGroup && ((ViewGroup)parent).getClipChildren()))
			visible = rect.intersect(0, 0, width, height);
		if (visible && clipToPadding && (getPaddingLeft() != 0 || getPaddingTop() != 0
				|| getPaddingRight() != 0 || getPaddingBottom() != 0))
			visible = rect.intersect(getPaddingLeft(), getPaddingTop(),
				width - getPaddingRight(), height - getPaddingBottom());
		r.set((int)Math.floor(rect.left), (int)Math.floor(rect.top),
			(int)Math.ceil(rect.right), (int)Math.ceil(rect.bottom));
		return visible && (parent == null || parent.getChildVisibleRect(this, r, offset));
	}

	// Hierarchy-state dispatch: a ViewGroup saves/restores its own state (by
	// ID, via super) and then forwards to every child (public API contract).
	@Override
	protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
		super.dispatchSaveInstanceState(container);
		if (children != null) {
			for (View child : children)
				if (child.isSaveFromParentEnabled()) child.dispatchSaveInstanceState(container);
		}
	}

	@Override
	protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
		super.dispatchRestoreInstanceState(container);
		if (children != null) {
			for (View child : children)
				if (child.isSaveFromParentEnabled()) child.dispatchRestoreInstanceState(container);
		}
	}

	// RecyclerView and other containers save child state through their own
	// adapters. These entry points deliberately bypass child traversal.
	protected void dispatchFreezeSelfOnly(SparseArray<Parcelable> container) {
		super.dispatchSaveInstanceState(container);
	}
	protected void dispatchThawSelfOnly(SparseArray<Parcelable> container) {
		super.dispatchRestoreInstanceState(container);
	}

	/* clang-format off */
	static final int FLAG_CLIP_CHILDREN        = (1 << 0);
	static final int FLAG_INVALIDATE_REQUIRED  = (1 << 2);
	static final int FLAG_ANIMATION_DONE       = (1 << 4);
	static final int FLAG_OPTIMIZE_INVALIDATE  = (1 << 7);
	static final int FLAG_CLEAR_TRANSFORMATION = (1 << 8);
	@UnsupportedAppUsage
	protected static final int FLAG_USE_CHILD_DRAWING_ORDER = (1 << 10);
	@UnsupportedAppUsage
	protected static final int FLAG_SUPPORT_STATIC_TRANSFORMATIONS = (1 << 11);
	private static final int FLAG_MASK_FOCUSABILITY  = (3 << 17);
	public static final int FOCUS_BEFORE_DESCENDANTS = (1 << 17);
	public static final int FOCUS_AFTER_DESCENDANTS  = (2 << 17);
	public static final int FOCUS_BLOCK_DESCENDANTS  = (3 << 17);
	@UnsupportedAppUsage
	protected static final int FLAG_DISALLOW_INTERCEPT = (1 << 19);
	static final int FLAG_IS_TRANSITION_GROUP          = (1 << 24);
	static final int FLAG_IS_TRANSITION_GROUP_SET      = (1 << 25);
	static final int FLAG_TOUCHSCREEN_BLOCKS_FOCUS     = (1 << 26);
	/* clang-format on */

	public ArrayList<View> children;
	private ArrayList<View> detachedChildren;
	private boolean pendingHideDetachedChildren = false;
	private OnHierarchyChangeListener onHierarchyChangeListener;
	private LayoutTransition transition;
	private ViewGroupOverlay viewGroupOverlay;

	public ViewGroup(Context context) {
		this(context, null);
	}

	public ViewGroup(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public ViewGroup(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public ViewGroup(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr);

		children = new ArrayList<View>();
		detachedChildren = new ArrayList<View>();
	}

	public void addView(View child) {
		addView(child, -1);
	}

	public void addView(View child, int index) {
		LayoutParams params = child.getLayoutParams();
		if (params == null) {
			params = generateDefaultLayoutParams();
		}
		addView(child, index, params);
	}

	public void addView(View child, LayoutParams params) {
		addView(child, -1, params);
	}

	public void addView(View child, int width, int height) {
		final LayoutParams params = generateDefaultLayoutParams();
		params.width = width;
		params.height = height;
		addView(child, params);
	}

	public void addView(View child, int index, LayoutParams params) {
		addViewInternal(child, index, params, true);
		requestLayout();
	}

	public void onViewAdded(View view) {}
	public void onViewRemoved(View view) {}

	protected void addViewInternal(View child, int index, LayoutParams params, boolean callOnViewAdded) {
		if (child.parent == this)
			return;
		if (!checkLayoutParams(params))
			params = generateLayoutParams(params);

		child.parent = this;
		child.setLayoutParams(params);
		if (index < 0)
			index = children.size();
		children.add(index, child);
		int sortedIndex = index;
		for (View v : children) {
			if (v.getZ() != 0.0f) {
				// sort children by z-order
				ArrayList<View> sortedChildren = new ArrayList<View>(children);
				sortedChildren.sort(new Comparator<View>() {
					@Override
					public int compare(View o1, View o2) {
						return Float.compare(o1.getZ(), o2.getZ());
					}
				});
				sortedIndex = sortedChildren.indexOf(child);
				break;
			}
		}
		native_addView(widget, child.widget, sortedIndex, params);
		if (callOnViewAdded) {
			onViewAdded(child);
			if (onHierarchyChangeListener != null)
				onHierarchyChangeListener.onChildViewAdded(this, child);
		}
	}

	// One child owns an unsplit gesture until UP/CANCEL; never hit-test MOVE
	// against a new child. This follows AOSP's non-splitting dispatch path.
	private View touchTarget;

	private boolean dispatchToChild(View child, MotionEvent event, boolean cancel) {
		MotionEvent local = MotionEvent.obtain(event);
		local.offsetLocation(getScrollX() - child.getX(), getScrollY() - child.getY());
		if (cancel) local.setAction(MotionEvent.ACTION_CANCEL);
		try { return child.dispatchTouchEvent(local); }
		finally { local.recycle(); }
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			if (touchTarget != null) dispatchToChild(touchTarget, event, true);
			touchTarget = null;
			disallowIntercept = false;
		}
		boolean canceled = action == MotionEvent.ACTION_CANCEL;
		boolean intercepted = false;
		if (action == MotionEvent.ACTION_DOWN || touchTarget != null) {
			if (!disallowIntercept) intercepted = onInterceptTouchEvent(event);
		} else {
			intercepted = true;
		}
		boolean handled = false;
		if (!canceled && !intercepted && action == MotionEvent.ACTION_DOWN) {
			// AOSP dispatches higher-Z children first; equal-Z children retain
			// reverse drawing order. Capture the order once for this DOWN.
			ArrayList<View> touchChildren = new ArrayList<View>(children);
			touchChildren.sort(new Comparator<View>() {
				@Override public int compare(View a, View b) {
					return Float.compare(a.getZ(), b.getZ());
				}
			});
			for (int i = touchChildren.size() - 1; i >= 0; i--) {
				View child = touchChildren.get(i);
				if (child.getParent() != this || child.getVisibility() != VISIBLE) continue;
				float x = event.getX() + getScrollX() - child.getX();
				float y = event.getY() + getScrollY() - child.getY();
				if (x < 0 || y < 0 || x >= child.getWidth() || y >= child.getHeight()) continue;
				if (dispatchToChild(child, event, false)) {
					touchTarget = child;
					handled = true;
					break;
				}
			}
		}
		if (touchTarget == null) {
			handled = super.dispatchTouchEvent(event);
		} else if (!handled) {
			boolean cancelChild = canceled || intercepted || touchTarget.getParent() != this;
			handled = dispatchToChild(touchTarget, event, cancelChild);
			if (cancelChild) touchTarget = null;
		}
		if (canceled || action == MotionEvent.ACTION_UP) {
			touchTarget = null;
			disallowIntercept = false;
		}
		return handled;
	}

	protected boolean addViewInLayout(View child, int index, LayoutParams params) {
		addViewInternal(child, index, params, true);
		return true;
	}

	protected boolean addViewInLayout(View child, int index, LayoutParams params, boolean preventRequestLayout) {
		addViewInternal(child, index, params, true);
		if (!preventRequestLayout)
			requestLayout();
		return true;
	}

	// This internal method is used to share code between removeView and removeViewInLayout.
	// Reusing removeView in removeViewInLayout is not possible, because e.g.
	// ViewPager overrides removeView to call removeViewInLayout
	protected void removeViewInternal(View child) {
		if (child == null || child.parent != this)
			return;
		child.parent = null;
		children.remove(child);
		native_removeView(widget, child.widget);
		onViewRemoved(child);
		if (onHierarchyChangeListener != null) {
			onHierarchyChangeListener.onChildViewRemoved(this, child);
		}
	}

	public void removeView(View child) {
		removeViewInternal(child);
		requestLayout();
	}

	public void removeViewInLayout(View child) {
		removeViewInternal(child);
	}

	public void removeViewAt(int index) {
		removeView(children.get(index));
	}

	public void removeViews(int start, int count) {
		int childCount = children.size();
		if (start < 0 || count < 0 || count > childCount || start > childCount - count)
			throw new IndexOutOfBoundsException();
		for (int i = 0; i < count; i++)
			removeViewInternal(children.get(start));
		requestLayout();
	}

	public void removeAllViews() {
		for (Iterator<View> it = children.iterator(); it.hasNext();) {
			View child = it.next();
			child.parent = null;
			it.remove();
			native_removeView(widget, child.widget);
			onViewRemoved(child);
			if (onHierarchyChangeListener != null) {
				onHierarchyChangeListener.onChildViewRemoved(this, child);
			}
		}
		requestLayout();
	}

	public void detachViewFromParent(int index) {
		detachViewFromParent(getChildAt(index));
	}

	public void attachViewToParent(View view, int index, LayoutParams params) {
		if (!detachedChildren.remove(view)) {
			addViewInternal(view, index, params, false);
		}
		if (!checkLayoutParams(params))
			params = generateLayoutParams(params);

		view.parent = this;
		view.setLayoutParams(params);
		if (index < 0)
			index = children.size();
		children.add(index, view);
		view.native_setVisibility(view.widget, view.getVisibility(), view.getAlpha(), view.isEnabled());
	}

	protected void removeDetachedView(View child, boolean animate) {
		if (!detachedChildren.remove(child))
			return;
		child.parent = null;
		native_removeView(widget, child.widget);
		if (onHierarchyChangeListener != null) {
			onHierarchyChangeListener.onChildViewRemoved(this, child);
		}
	}

	@Override
	protected void dispatchVisibilityChanged(View changedView, int visibility) {
		if (children == null) // happens if this gets called during super constructor
			return;

		for (View child : children) {
			child.dispatchVisibilityChanged(changedView, visibility);
		}
	}

	protected native void native_addView(long widget, long child, int index, LayoutParams params);
	protected native void native_removeView(long widget, long child);
	@Override
	protected native void native_drawChildren(long widget, long snapshot);
	protected native void native_drawChild(long widget, long child, long snapshot);
	@Override
	protected void native_drawContent(long widget, long snapshot) {}

	public View getChildAt(int index) {
		try {
			return children.get(index);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}

	public int indexOfChild(View child) {
		return children.indexOf(child);
	}

	public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
		view.setLayoutParams(params);
		view.requestLayout();
	}

	public LayoutParams generateLayoutParams(AttributeSet attrs) {
		return new LayoutParams(getContext(), attrs);
	}
	protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
		return p;
	}

	public void bringChildToFront(View child) {
		int index = children.indexOf(child);
		if (index < 0 || index == children.size() - 1)
			return;
		children.remove(index);
		children.add(child);
		atlUpdateZOrder();
	}

	void atlUpdateZOrder() {
		ArrayList<View> drawingOrder = new ArrayList<View>(children);
		drawingOrder.sort(new Comparator<View>() {
			@Override
			public int compare(View a, View b) { return Float.compare(a.getZ(), b.getZ()); }
		});
		// Reorder existing widgets without detaching their surfaces or firing
		// hierarchy add/remove callbacks. Equal Z values retain child order.
		for (View view : drawingOrder)
			native_addView(widget, view.widget, children.size(), null);
		requestLayout();
		invalidate();
	}

	/**
	 * Returns the number of children in the group.
	 *
	 * @return a positive integer representing the number of children in
	 *         the group
	 */
	public int getChildCount() {
		return children.size();
	}

	public void setMotionEventSplittingEnabled(boolean enabled) {}

	public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
		this.onHierarchyChangeListener = listener;
	}

	protected boolean checkLayoutParams(LayoutParams params) {
		return true;
	}

	public LayoutTransition getLayoutTransition() {
		if (transition == null)
			transition = new LayoutTransition();
		return transition;
	}

	public static int getChildMeasureSpec(int spec, int padding, int childDimension) {
		int specMode = MeasureSpec.getMode(spec);
		int specSize = MeasureSpec.getSize(spec);
		int size = Math.max(0, specSize - padding);
		int resultSize = 0;
		int resultMode = 0;
		switch (specMode) {
			// Parent has imposed an exact size on us
			case MeasureSpec.EXACTLY:
				if (childDimension >= 0) {
					resultSize = childDimension;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.MATCH_PARENT) {
					// Child wants to be our size. So be it.
					resultSize = size;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.WRAP_CONTENT) {
					// Child wants to determine its own size. It can't be
					// bigger than us.
					resultSize = size;
					resultMode = MeasureSpec.AT_MOST;
				}
				break;
			// Parent has imposed a maximum size on us
			case MeasureSpec.AT_MOST:
				if (childDimension >= 0) {
					// Child wants a specific size... so be it
					resultSize = childDimension;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.MATCH_PARENT) {
					// Child wants to be our size, but our size is not fixed.
					// Constrain child to not be bigger than us.
					resultSize = size;
					resultMode = MeasureSpec.AT_MOST;
				} else if (childDimension == LayoutParams.WRAP_CONTENT) {
					// Child wants to determine its own size. It can't be
					// bigger than us.
					resultSize = size;
					resultMode = MeasureSpec.AT_MOST;
				}
				break;
			// Parent asked to see how big we want to be
			case MeasureSpec.UNSPECIFIED:
				if (childDimension >= 0) {
					// Child wants a specific size... let them have it
					resultSize = childDimension;
					resultMode = MeasureSpec.EXACTLY;
				} else if (childDimension == LayoutParams.MATCH_PARENT) {
					// Child wants to be our size... find out how big it should
					// be
					resultSize = 0; // View.sUseZeroUnspecifiedMeasureSpec ? 0 : size;
					resultMode = MeasureSpec.UNSPECIFIED;
				} else if (childDimension == LayoutParams.WRAP_CONTENT) {
					// Child wants to determine its own size.... find out how
					// big it should be
					resultSize = 0; // View.sUseZeroUnspecifiedMeasureSpec ? 0 : size;
					resultMode = MeasureSpec.UNSPECIFIED;
				}
				break;
		}
		// noinspection ResourceType
		return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
	}

	protected void measureChildWithMargins(View child,
	                                       int parentWidthMeasureSpec, int widthUsed,
	                                       int parentHeightMeasureSpec, int heightUsed) {
		final MarginLayoutParams lp = (MarginLayoutParams)child.getLayoutParams();
		final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width);
		final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height);
		child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
	}

	protected void measureChild(View child, int parentWidthMeasureSpec,
	                            int parentHeightMeasureSpec) {
		final LayoutParams lp = child.getLayoutParams();
		final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight, lp.width);
		final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, lp.height);
		child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
	}

	protected void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
		for (View child : children) {
			measureChild(child, widthMeasureSpec, heightMeasureSpec);
		}
	}

	public void setAddStatesFromChildren(boolean addsStates) {}

	public View getFocusedChild() { return null; }

	public int getDescendantFocusability() { return 0; }

	public void startViewTransition(View view) {}
	public void endViewTransition(View view) {}

	protected LayoutParams generateDefaultLayoutParams() {
		return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	}

	public void focusableViewAvailable(View v) {}

	@Override
	public void setGravity(int gravity) {
		super.setGravity(gravity);
		// update children as necessary
		for (View child : children) {
			LayoutParams params = child.getLayoutParams();
			if (params.gravity == -1)
				child.setLayoutParams(params);
		}
	}

	private boolean childrenDrawingOrderEnabled;
	protected void setChildrenDrawingOrderEnabled(boolean enabled) {
		childrenDrawingOrderEnabled = enabled; invalidate();
	}
	protected boolean isChildrenDrawingOrderEnabled() { return childrenDrawingOrderEnabled; }
	protected int getChildDrawingOrder(int childCount, int drawingPosition) { return drawingPosition; }

	public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
		for (ViewGroup iter = this; iter != null; iter = iter.parent instanceof ViewGroup ? (ViewGroup)iter.parent : null)
			iter.disallowIntercept = disallowIntercept;
	}


	@Override
	public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
		return false;
	}

	@Override
	public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
		return false;
	}

	@Override
	public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
		return false;
	}

	public void setClipToPadding(boolean clipToPadding) { this.clipToPadding = clipToPadding; }

	public View findViewById(int id) {
		Slog.v(TAG, "findViewById: looking for id: " + String.format("%x", id) + "(" + getResources().getAssets().getResourceName(id) + ")"
		            + " | checking: " + this + ",id: " + String.format("%x", this.getId()) + ", id_str: " + this.getIdName());
		if (this.id == id) {
			Slog.v(TAG, "findViewById: found: " + this + " | id: " + String.format("%x", this.getId()) + ", id_str: " + this.getIdName());
			return this;
		}
		for (View child : children) {
			View result = child.findViewById(id);
			if (result != null)
				return result;
		}
		return null;
	}

	public void detachViewFromParent(View view) {
		children.remove(view);
		view.parent = null;
		detachedChildren.add(view);
		if (!pendingHideDetachedChildren) {
			pendingHideDetachedChildren = true;
			post(new Runnable() {
				@Override
				public void run() {
					pendingHideDetachedChildren = false;
					for (View child : detachedChildren)
						child.native_setVisibility(child.widget, GONE, 0, false);
				}
			});
		}
	}

	public void setTouchscreenBlocksFocus(boolean touchscreenBlocksFocus) {}

	public void setClipChildren(boolean clipChildren) { this.clipChildren = clipChildren; }

	protected void dispatchSetPressed(boolean pressed) {}

	@Override
	public View findViewWithTag(Object tag) {
		if (Objects.equals(tag, getTag()))
			return this;
		for (View child : children) {
			View result = child.findViewWithTag(tag);
			if (result != null)
				return result;
		}
		return null;
	}

	public void setLayoutTransition(LayoutTransition transition) {
		this.transition = transition;
	}

	private Boolean customDrawChild;

	boolean hasCustomDrawChild() {
		if (customDrawChild == null) {
			customDrawChild = false;
			for (Class<?> type = getClass(); type != ViewGroup.class; type = type.getSuperclass()) {
				try {
					type.getDeclaredMethod("drawChild", Canvas.class, View.class, long.class);
					customDrawChild = true;
					break;
				} catch (NoSuchMethodException e) { }
			}
		}
		return customDrawChild || childrenDrawingOrderEnabled;
	}

	// AOSP ViewGroup.dispatchDraw/buildOrderedChildList: custom order is resolved
	// immediately before each child unless elevation requires a preordered list.
	// Some hosts interleave their own drawables inside getChildDrawingOrder().
	private int drawingIndex(int count, int position) {
		int index = childrenDrawingOrderEnabled ? getChildDrawingOrder(count, position) : position;
		if (index < 0 || index >= count)
			throw new IndexOutOfBoundsException("getChildDrawingOrder returned " + index + " for " + count + " children");
		return index;
	}
	void drawCustomChildren(Canvas canvas) {
		int count = children.size();
		boolean hasZ = false;
		for (View child : children) if (child.getZ() != 0) { hasZ = true; break; }
		ArrayList<View> ordered = null;
		if (hasZ) {
			ordered = new ArrayList<View>(count);
			for (int i = 0; i < count; i++) ordered.add(children.get(drawingIndex(count, i)));
			ordered.sort(new Comparator<View>() {
				@Override public int compare(View a, View b) { return Float.compare(a.getZ(), b.getZ()); }
			});
		}
		long time = getDrawingTime();
		for (int i = 0; i < count; i++) {
			View child = ordered == null ? children.get(drawingIndex(count, i)) : ordered.get(i);
			if (child.getVisibility() == VISIBLE || child.getAnimation() != null)
				drawChild(canvas, child, time);
		}
	}

	public boolean drawChild(Canvas canvas, View child, long drawingTime) {
		if (canvas instanceof GskCanvas)
			native_drawChild(widget, child.widget, ((GskCanvas)canvas).snapshot);
		return false;
	}

	protected void cleanupLayoutState(View child) {}

	public boolean shouldDelayChildPressedState() { return false; }

	public void removeAllViewsInLayout() {
		for (int i = getChildCount() - 1; i >= 0; i--) {
			removeViewInLayout(getChildAt(i));
		}
	}

	public void offsetChildrenTopAndBottom(int offset) {
		for (View child : children)
			child.offsetTopAndBottom(offset);
	}

	private native void native_offsetRect(long from_widget, long to_widget, Rect rect);

	public final void offsetDescendantRectToMyCoords(View descendant, Rect rect) {
		native_offsetRect(descendant.widget, widget, rect);
	}

	public final void offsetRectIntoDescendantCoords(View descendant, Rect rect) {
		native_offsetRect(widget, descendant.widget, rect);
	}

	public boolean getClipToPadding() { return clipToPadding; }

	public boolean isTransitionGroup() { return false; }

	public static class LayoutParams {
		public static final int FILL_PARENT = -1;
		public static final int MATCH_PARENT = -1;
		public static final int WRAP_CONTENT = -2;

		public int width = 0;
		public int height = 0;
		public float weight = 0;
		public int gravity = -1;

		public LayoutParams() {
			// FIXME
		}

		public LayoutParams(int width, int height) {
			this.width = width;
			this.height = height;
		}

		public LayoutParams(int width, int height, float weight) {
			this.width = width;
			this.height = height;
			this.weight = weight;
		}

		/**
		 * Copy constructor. Clones the width and height values of the source.
		 *
		 * @param source The layout params to copy from.
		 */
		public LayoutParams(LayoutParams source) {
			this.width = source.width;
			this.height = source.height;
		}

		public LayoutParams(Context context, AttributeSet attrs) {
			TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ViewGroup_Layout);
			setBaseAttributes(a, R.styleable.ViewGroup_Layout_layout_width, R.styleable.ViewGroup_Layout_layout_height);
			a.recycle();
			a = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.layout_gravity});
			gravity = a.getInt(0, -1);
			a.recycle();
		}

		protected void setBaseAttributes(TypedArray a, int widthAttr, int heightAttr) {
			width = a.getLayoutDimension(widthAttr, "layout_width");
			height = a.getLayoutDimension(heightAttr, "layout_height");
		}

		public void resolveLayoutDirection(int layoutDirection) {}

		/**
		 * Used to animate layouts.
		 */
		//public LayoutAnimationController.AnimationParameters layoutAnimationParameters;
	}

	public static class MarginLayoutParams extends ViewGroup.LayoutParams {
		public int leftMargin;
		public int topMargin;
		public int rightMargin;
		public int bottomMargin;

		public MarginLayoutParams() {
			super();
		}

		public MarginLayoutParams(LayoutParams params) {
			super();
			width = params.width;
			height = params.height;
		}

		public MarginLayoutParams(int width, int height) {
			super(width, height);
		}

		public MarginLayoutParams(int width, int height, float weight) {
			super(width, height, weight);
		}

		public MarginLayoutParams(Context context, AttributeSet attributeSet) {
			super(context, attributeSet);
			TypedArray a = context.obtainStyledAttributes(attributeSet, com.android.internal.R.styleable.ViewGroup_MarginLayout);

			int margin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_margin, -1);

			int marginVertical = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginVertical, -1);
			int marginHorizontal = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginHorizontal, -1);

			int marginStart = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginStart, -1);
			int marginEnd = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginEnd, -1);

			leftMargin = 0;
			topMargin = 0;
			rightMargin = 0;
			bottomMargin = 0;

			if (margin >= 0) {
				leftMargin = margin;
				topMargin = margin;
				rightMargin = margin;
				bottomMargin = margin;
			} else {
				if (marginVertical >= 0) {
					topMargin = marginVertical;
					bottomMargin = marginVertical;
				} else {
					topMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginTop, 0);
					bottomMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginBottom, 0);
				}

				if (marginHorizontal >= 0) {
					leftMargin = marginHorizontal;
					rightMargin = marginHorizontal;
				} else {
					leftMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginLeft, 0);
					rightMargin = a.getDimensionPixelSize(com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_marginRight, 0);

					if (marginStart >= 0) {
						leftMargin = marginStart;
					}

					if (marginEnd >= 0) {
						rightMargin = marginEnd;
					}
				}
			}

			a.recycle();
		}

		public MarginLayoutParams(ViewGroup.MarginLayoutParams source) {
			super();
			width = source.width;
			height = source.height;
			leftMargin = source.leftMargin;
			topMargin = source.topMargin;
			rightMargin = source.rightMargin;
			bottomMargin = source.bottomMargin;
		}

		public void setMargins(int left, int top, int right, int bottom) {
			leftMargin = left;
			topMargin = top;
			rightMargin = right;
			bottomMargin = bottom;
		}

		public int getMarginStart() {
			return leftMargin;
		}

		public int getMarginEnd() {
			return rightMargin;
		}

		public void setMarginStart(int marginStart) {
			leftMargin = marginStart;
		}
		public void setMarginEnd(int marginEnd) {
			rightMargin = marginEnd;
		}

		public int getLayoutDirection() { return LAYOUT_DIRECTION_LTR; }

		public boolean isMarginRelative() { return false; }
	}

	public interface OnHierarchyChangeListener {
		public void onChildViewAdded(View parent, View child);
		public void onChildViewRemoved(View parent, View child);
	}

	@Override
	public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onNestedScrollAccepted'");
	}

	@Override
	public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onNestedPreScroll'");
	}

	@Override
	public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onNestedScroll'");
	}

	@Override
	public void onStopNestedScroll(View target) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onStopNestedScroll'");
	}

	public void requestChildFocus(View child, View focused) {}

	public boolean getClipChildren() {
		return clipChildren;
	}

	public native boolean native_dispatchTouchEvent(long widget, MotionEvent event, double x, double y);

	@Override
	public void onDescendantInvalidated(View child, View target) {
		// A custom drawChild path snapshots descendants outside their native
		// container's snapshot. Its cached GTK node must be invalidated too.
		if (hasCustomDrawChild()) nativeInvalidate(widget);
		if (getParent() != null)
			getParent().onDescendantInvalidated(this, target);
	}

	public boolean getTouchscreenBlocksFocus() { return false; }

	public ViewGroupOverlay getOverlay() {
		if (viewGroupOverlay == null)
			viewGroupOverlay = new ViewGroupOverlay();
		return viewGroupOverlay;
	}

	public void scheduleLayoutAnimation() {}

	public boolean addStatesFromChildren() { return false; }
}
