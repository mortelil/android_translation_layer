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
		View detached = new View(context);
		detached.setAutofillHints("username");
		detached.setImportantForAutofill(2);
		if (!"username".equals(detached.getAutofillHints()[0]) || detached.getImportantForAutofill() != 2)
			throw new AssertionError("Autofill metadata must survive view configuration");
		detached.setAutofillHints((String[])null);
		if (detached.getAutofillHints() != null)
			throw new AssertionError("Autofill hints must be clearable");
		for (android.widget.TextView text : new android.widget.TextView[] {
		    new android.widget.TextView(context), new android.widget.EditText(context)}) {
			text.setFontFeatureSettings("liga=0");
			if (!"liga=0".equals(text.getFontFeatureSettings()))
				throw new AssertionError("Font feature settings");
			text.setFontFeatureSettings(null);
			if (text.getFontFeatureSettings() != null)
				throw new AssertionError("Reset font feature settings");
		}
		android.view.ViewTreeObserver observer = detached.getViewTreeObserver();
		final int[] preDrawCalls = {0};
		android.view.ViewTreeObserver.OnPreDrawListener listener = new android.view.ViewTreeObserver.OnPreDrawListener() {
			@Override public boolean onPreDraw() { preDrawCalls[0]++; return true; }
		};
		observer.addOnPreDrawListener(listener);
		int callsBeforeDispatch = preDrawCalls[0];
		if (detached.getViewTreeObserver() != observer)
			throw new AssertionError("Detached view must retain its observer and registered listeners");
		detached.getViewTreeObserver().dispatchOnPreDraw();
		if (preDrawCalls[0] != callsBeforeDispatch + 1)
			throw new AssertionError("Detached observer lost its pre-draw listener");
		detached.getViewTreeObserver().removeOnPreDrawListener(listener);
		System.out.println("PASS: detached ViewTreeObserver retains identity and pre-draw listeners");
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
		FrameLayout range = new FrameLayout(context);
		View first = new View(context), removedOne = new View(context), removedTwo = new View(context), last = new View(context);
		range.addView(first);
		range.addView(removedOne);
		range.addView(removedTwo);
		range.addView(last);
		range.removeViews(1, 2);
		if (range.getChildCount() != 2 || range.getChildAt(0) != first || range.getChildAt(1) != last
		    || removedOne.getParent() != null || removedTwo.getParent() != null)
			throw new AssertionError("removeViews must remove the selected contiguous range and detach its children");
		try {
			range.removeViews(1, 2);
			throw new AssertionError("removeViews must reject a range extending past the child list");
		} catch (IndexOutOfBoundsException expected) {
			if (range.getChildCount() != 2 || range.getChildAt(1) != last)
				throw new AssertionError("Invalid removeViews range must not partially mutate the child list");
		}
		System.out.println("PASS: ViewGroup.removeViews range removal and bounds checking");
	}
}
