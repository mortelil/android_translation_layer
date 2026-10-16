// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.view.CollapsibleActionView;
import android.view.View;

public final class TestCollapsibleActionView {
    public static final class SearchAction extends View implements CollapsibleActionView {
        int expanded, collapsed;
        public SearchAction(Context context) { super(context); }
        public void onActionViewExpanded() { expanded++; }
        public void onActionViewCollapsed() { collapsed++; }
    }

    public static void run(Context context) throws Exception {
        // AppCompat inflates application action views through this reflection path.
        Class<?> type = Class.forName("TestCollapsibleActionView$SearchAction", true,
                context.getClassLoader());
        SearchAction view = (SearchAction) type.getConstructor(Context.class).newInstance(context);
        CollapsibleActionView action = (CollapsibleActionView) view;
        action.onActionViewExpanded();
        action.onActionViewCollapsed();
        action.onActionViewExpanded();
        if (view.expanded != 2 || view.collapsed != 1)
            throw new AssertionError("Action view callbacks did not reach the application");
        System.out.println("PASS: reflective application CollapsibleActionView loading and callbacks");
    }
}
