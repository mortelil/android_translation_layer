// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

public final class TestPopupContracts {
    public static void run(Context context) {
        PopupWindow popup = new PopupWindow(context);
        popup.setAttachedInDecor(false);
        TextView content = new TextView(context);
        ColorDrawable contentBackground = new ColorDrawable(0xffeeeeee);
        content.setBackground(contentBackground);
        popup.setContentView(content);
        popup.setBackgroundDrawable(new ColorDrawable(0xff123456));
        if (content.getBackground() != contentBackground)
            throw new AssertionError("Popup overwrote application content background");
        popup.setContentView(content);
        if (popup.getContentView() != content)
            throw new AssertionError("Repeated setContentView changed content");
        if (popup.isAttachedInDecor()) throw new AssertionError("Explicit decor policy lost");
        popup.setAttachedInDecor(true);
        popup.setOverlapAnchor(true);
        if (!popup.isAttachedInDecor() || !popup.getOverlapAnchor())
            throw new AssertionError("Popup configuration not retained");
        popup.setContentView(null);
        if (popup.getContentView() != null) throw new AssertionError("Content not cleared");
        for (final int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            TextView text = new TextView(context) {
                public int getLayoutDirection() { return direction; }
            };
            text.setPadding(3, 4, 7, 8);
            ColorDrawable left = new ColorDrawable(0xff000000);
            ColorDrawable right = new ColorDrawable(0xff000000);
            left.setBounds(0, 0, 11, 12);
            right.setBounds(0, 0, 19, 20);
            text.setCompoundDrawables(left, null, right, null);
            int start = direction == View.LAYOUT_DIRECTION_LTR ? 14 : 26;
            int end = direction == View.LAYOUT_DIRECTION_LTR ? 26 : 14;
            if (text.getCompoundPaddingStart() != start || text.getCompoundPaddingEnd() != end
                    || text.getCompoundPaddingTop() != 4 || text.getCompoundPaddingBottom() != 8)
                throw new AssertionError("Relative padding/drawable bounds differ");
            text.setCompoundDrawables(null, null, null, null);
            if (text.getCompoundPaddingLeft() != 3 || text.getCompoundPaddingRight() != 7)
                throw new AssertionError("Removed drawable still contributes padding");
        }
        System.out.println("PASS: popup configuration, null content and LTR/RTL compound padding");
    }
}
