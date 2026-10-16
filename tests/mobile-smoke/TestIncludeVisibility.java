// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.view.*;
public class TestIncludeVisibility {
    public static void run(Context context) {
        int resource = context.getResources().getIdentifier("include_visibility", "layout", context.getPackageName());
        if (resource == 0) throw new AssertionError("Include fixture not packaged");
        View root = LayoutInflater.from(context).inflate(resource, null);
        String[] ids = {"hidden", "invisible", "visible", "inherited", "merged"};
        int[] expected = {View.GONE, View.INVISIBLE, View.VISIBLE, View.INVISIBLE, View.VISIBLE};
        for (int i = 0; i < ids.length; i++) {
            int id = context.getResources().getIdentifier(ids[i], "id", context.getPackageName());
            View child = root.findViewById(id);
            if (child == null || child.getVisibility() != expected[i])
                throw new AssertionError("Incorrect include visibility/id: " + ids[i]);
        }
        System.out.println("PASS: include visibility overrides, inherited visibility, IDs and merge semantics");
    }
}
