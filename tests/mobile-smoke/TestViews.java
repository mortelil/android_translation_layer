// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

public class TestViews {
	static class Observer extends FrameLayout {
		int count;
		View child, target;
		Observer(Context context) { super(context); }
		@Override
		public void onDescendantInvalidated(View child, View target) {
			this.child = child;
			this.target = target;
			count++;
			super.onDescendantInvalidated(child, target);
		}
	}
	public static void run(Context context) {
		Observer root = new Observer(context);
		FrameLayout middle = new FrameLayout(context);
		View leaf = new View(context);
		root.addView(middle);
		middle.addView(leaf);
		int before = root.count;
		leaf.invalidate();
		if (root.count != before + 1 || root.child != middle || root.target != leaf)
			throw new AssertionError("Descendant invalidation");
		View second = new View(context);
		middle.addView(second);
		leaf.bringToFront();
		if (middle.getChildAt(0) != second || middle.getChildAt(1) != leaf || leaf.getParent() != middle)
			throw new AssertionError("Child stacking");
		leaf.bringToFront();
		if (middle.getChildCount() != 2)
			throw new AssertionError("Stacking changed child count");
		System.out.println("PASS: descendant invalidation and child stacking");
	}
}
