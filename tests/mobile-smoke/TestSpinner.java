// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.TextView;

public final class TestSpinner {
    public static void run(final Context context) {
        Spinner spinner = new Spinner(context);
        spinner.setAdapter(new BaseAdapter() {
            public int getCount() { return 3; }
            public Object getItem(int p) { return "Choice " + p; }
            public long getItemId(int p) { return 100 + p; }
            public View getView(int p, View old, ViewGroup parent) {
                TextView text = new TextView(context);
                text.setText((String)getItem(p));
                return text;
            }
        });
        final int[] calls = {0};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent.getSelectedItemPosition() != position || id != 100 + position)
                    throw new AssertionError("Spinner callback/state disagreement");
                calls[0]++;
            }
            public void onNothingSelected(AdapterView<?> parent) { calls[0]++; }
        });
        spinner.setSelection(2);
        if (spinner.getSelectedItemPosition() != 2 || spinner.getSelectedItemId() != 102
                || !"Choice 2".equals(spinner.getSelectedItem()) || calls[0] != 1)
            throw new AssertionError("Programmatic selection did not update native/Java state");
        spinner.setSelection(2, true);
        if (calls[0] != 1) throw new AssertionError("Duplicate selection callback");
        spinner.setOnItemSelectedListener(null);
        spinner.setSelection(1);
        if (spinner.getSelectedItemPosition() != 1 || calls[0] != 1)
            throw new AssertionError("Removed listener still called");
        System.out.println("PASS: Spinner selection, item ID, duplicate suppression and listener removal");
    }
}
