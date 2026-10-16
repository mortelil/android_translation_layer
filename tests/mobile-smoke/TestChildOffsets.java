// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
public class TestChildOffsets {
    public static void run(Context context) {
        FrameLayout group = new FrameLayout(context);
        View first = new View(context), second = new View(context);
        group.addView(first); group.addView(second);
        first.layout(3, 40, 23, 60); second.layout(7, 70, 27, 90);
        second.setVisibility(View.GONE);
        group.offsetChildrenTopAndBottom(-30);
        if (first.getTop()!=10 || first.getBottom()!=30 || first.getLeft()!=3
                || second.getTop()!=40 || second.getBottom()!=60)
            throw new AssertionError("All child bounds must move by the requested offset");
        group.offsetChildrenTopAndBottom(30);
        if (first.getTop()!=40 || second.getTop()!=70)
            throw new AssertionError("Inverse offset must restore child positions");
        System.out.println("PASS: child vertical offsets update visible and hidden child bounds");
    }
}
