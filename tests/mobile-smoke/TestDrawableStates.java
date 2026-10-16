// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

public final class TestDrawableStates {
    static class StatefulView extends View {
        boolean checked;
        int changes;
        StatefulView(Context context) { super(context); }
        protected int[] onCreateDrawableState(int extra) {
            int[] states = super.onCreateDrawableState(extra + 1);
            if (checked) mergeDrawableStates(states, new int[]{android.R.attr.state_checked});
            return states;
        }
        protected void drawableStateChanged() { changes++; super.drawableStateChanged(); }
        static int[] merge(int[] base, int[] extra) { return mergeDrawableStates(base, extra); }
    }
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    public static void run(Context context) {
        int[] base = {android.R.attr.state_enabled, 0, 0};
        int[] extra = {android.R.attr.state_checked};
        check(StatefulView.merge(base, extra) == base && base[1] == extra[0] && base[2] == 0,
                "Drawable merge must extend the supplied array in place");
        check(extra[0] == android.R.attr.state_checked, "Additional state changed");
        try { StatefulView.merge(new int[]{1}, new int[]{2}); throw new AssertionError("Missing capacity accepted"); }
        catch (ArrayIndexOutOfBoundsException expected) {}
        StatefulView view = new StatefulView(context);
        StateListDrawable background = new StateListDrawable();
        ColorDrawable disabled = new ColorDrawable(0xff555555);
        ColorDrawable checked = new ColorDrawable(0xff00ff00);
        ColorDrawable pressed = new ColorDrawable(0xffff0000);
        ColorDrawable selected = new ColorDrawable(0xff0000ff);
        ColorDrawable activated = new ColorDrawable(0xffffff00);
        ColorDrawable normal = new ColorDrawable(0xffeeeeee);
        background.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        background.addState(new int[]{android.R.attr.state_checked}, checked);
        background.addState(new int[]{android.R.attr.state_pressed}, pressed);
        background.addState(new int[]{android.R.attr.state_selected}, selected);
        background.addState(new int[]{android.R.attr.state_activated}, activated);
        background.addState(new int[]{}, normal);
        view.setBackground(background);
        check(background.getCurrent() == normal, "Initial enabled state not delivered");
        view.setSelected(true);
        check(view.isSelected() && background.getCurrent() == selected, "Selected selector");
        int changes = view.changes;
        view.setSelected(true);
        check(changes == view.changes, "Duplicate selected refresh");
        view.setSelected(false); view.setActivated(true);
        check(view.isActivated() && background.getCurrent() == activated, "Activated selector");
        view.setPressed(true);
        check(background.getCurrent() == pressed, "Pressed selector");
        view.checked = true; view.refreshDrawableState();
        check(background.getCurrent() == checked, "App-defined checked state ignored");
        view.setEnabled(false);
        check(background.getCurrent() == disabled, "Disabled state incorrectly reports enabled");
        view.setEnabled(true);
        check(background.getCurrent() == checked, "Re-enable lost custom state");
        view.checked = false; view.setActivated(false); view.refreshDrawableState();
        check(background.getCurrent() == normal, "Selector failed to return to default");
        StateListDrawable sparse = new StateListDrawable();
        sparse.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sparse.setState(new int[]{android.R.attr.state_pressed});
        check(sparse.getCurrent() == pressed, "Sparse selector match");
        sparse.setState(new int[]{});
        check(sparse.getCurrent() == null, "Unmatched selector retained stale child");
        android.widget.CheckedTextView row = new android.widget.CheckedTextView(context);
        row.setCheckMarkDrawable(background);
        check(row.getCheckMarkDrawable() == background, "Checkmark identity");
        row.setChecked(true);
        check(row.isChecked() && background.getCurrent() == checked, "Checkmark checked state");
        row.toggle();
        check(!row.isChecked() && background.getCurrent() == normal, "Checkmark toggle");
        ColorDrawable mark = new ColorDrawable(0xff12ab34) {
            public int getIntrinsicWidth() { return 12; }
            public int getIntrinsicHeight() { return 10; }
        };
        row.setCheckMarkDrawable(mark);
        row.setPadding(3, 4, 5, 6);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.layout(0, 0, 100, 40);
        android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(100, 40,
                android.graphics.Bitmap.Config.ARGB_8888);
        row.onDraw(new android.graphics.Canvas(pixels));
        check(mark.getBounds().left == 83 && mark.getBounds().top == 15,
                "Checkmark end/vertical geometry");
        check(pixels.getPixel(88, 20) == 0xff12ab34, "Checkmark was not rendered");
        check(pixels.getPixel(80, 20) == 0, "Checkmark escaped its bounds");
        row.setCheckMarkDrawable((android.graphics.drawable.Drawable)null);
        check(row.getCheckMarkDrawable() == null, "Checkmark removal");
        System.out.println("PASS: drawable state extension, selectors and state-change dispatch");
    }
}
