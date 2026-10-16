// SPDX-License-Identifier: GPL-3.0-only
package org.atl.widgetcolors;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.drawable.ColorDrawable;
import android.widget.*;
public class ImageScaleActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(0xffffffff);
        for (int i=0; i<2; i++) {
            ImageButton button = new ImageButton(this);
            ColorDrawable icon = new ColorDrawable(i==0 ? 0xffff0000 : 0xff0000ff) {
                @Override public int getIntrinsicWidth() { return 24; }
                @Override public int getIntrinsicHeight() { return 24; }
            };
            button.setImageDrawable(icon);
            button.setPadding(0,0,0,0);
            button.setBackgroundColor(0xffffffff);
            button.setScaleType(i==0 ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.FIT_XY);
            root.addView(button, new LinearLayout.LayoutParams(80,80));
        }
        setContentView(root);
    }
}
