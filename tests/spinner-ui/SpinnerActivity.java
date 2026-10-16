// SPDX-License-Identifier: GPL-3.0-only
package org.atl.spinner;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

public class SpinnerActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new BaseAdapter() {
            public int getCount() { return 3; }
            public Object getItem(int p) { return "Choice " + p; }
            public long getItemId(int p) { return 100 + p; }
            public View getView(int p, View old, ViewGroup parent) {
                TextView text = new TextView(SpinnerActivity.this);
                text.setText((String)getItem(p));
                text.setPadding(10, 10, 10, 10);
                return text;
            }
        });
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int p, long id) {
                if (parent.getSelectedItemPosition() != p || id != 100 + p)
                    throw new AssertionError("native selection and Java disagree");
                System.out.println("SPINNER_SELECTED " + p + " id=" + id);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(spinner, new LinearLayout.LayoutParams(-1, 60));
        Spinner custom = new Spinner(this) {
            public boolean performClick() {
                System.out.println("SPINNER_CUSTOM_CLICK");
                return true;
            }
        };
        custom.setAdapter(spinner.getAdapter());
        root.addView(custom, new LinearLayout.LayoutParams(-1, 60));
        setContentView(root);
    }
}
