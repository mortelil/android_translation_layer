// SPDX-License-Identifier: GPL-3.0-only
package org.atl.widgetcolors;
import android.app.Activity;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ColorActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xffffffff);
        ColorStateList colors = new ColorStateList(
            new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}},
            new int[]{0xff777777, 0xff2468ac, 0xffd02030});
        for (int i = 0; i < 3; i++) {
            TextView label = new TextView(this);
            label.setText(new String[]{"Normal sample", "Selected sample", "Disabled sample"}[i]);
            label.setTextSize(28);
            label.setTextColor(colors);
            label.setSelected(i == 1);
            label.setEnabled(i != 2);
            int expected = new int[]{0xffd02030, 0xff2468ac, 0xff777777}[i];
            if (label.getCurrentTextColor() != expected || label.getPaint().getColor() != expected)
                throw new AssertionError("Text color contract differs at row " + i);
            root.addView(label, new LinearLayout.LayoutParams(-1, 75));
        }
        setContentView(root);
        System.out.println("WIDGET_COLOR_CONTRACTS_PASSED");
    }
}
