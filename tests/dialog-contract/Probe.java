/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.dialogcontract;
import android.app.*;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.TextView;
public class Probe extends Activity {
    int creations, starts, stops, shows, contentChanges;
    void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    boolean floating(Dialog dialog) {
        TypedValue value = new TypedValue();
        check(dialog.getContext().getTheme().resolveAttribute(android.R.attr.windowIsFloating, value, true), "windowIsFloating resolves");
        return value.data != 0;
    }
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView base = new TextView(this); base.setText("Dialog contract host"); setContentView(base);
        final Dialog full = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar) {
            protected void onCreate(Bundle state) { super.onCreate(state); creations++; }
            protected void onStart() { super.onStart(); starts++; }
            protected void onStop() { super.onStop(); stops++; }
            public void onContentChanged() { contentChanges++; }
        };
        Dialog floating = new Dialog(this, android.R.style.Theme_Material_Light_Dialog);
        check(full.getWindow().peekDecorView() == full.getWindow().getDecorView(), "peek returns existing decor");
        check(full.getWindow().peekDecorView() == full.getWindow().peekDecorView(), "peek keeps decor identity");
        full.getWindow().setContentView(android.R.layout.simple_list_item_1);
        check(contentChanges == 1, "resource content change callback");
        View inflated = full.getWindow().findViewById(android.R.id.text1);
        check(inflated instanceof TextView && inflated.getLayoutParams() != null, "resource content inflated with parent parameters");
        TextView replacement = new TextView(this);
        full.getWindow().setContentView(replacement, new ViewGroup.LayoutParams(123,45));
        check(full.getWindow().findViewById(android.R.id.text1) == null, "replaced content removed");
        check(replacement.getLayoutParams().width == 123 && replacement.getLayoutParams().height == 45, "explicit content dimensions preserved");
        check(full.getContext() != this, "dialog owns themed context");
        check(!floating(full) && floating(floating), "explicit themes stay distinct");
        check(full.getWindow().getAttributes().width == -1 && full.getWindow().getAttributes().height == -1, "nonfloating defaults fill window");
        full.create(); full.create();
        check(creations == 1 && starts == 0 && stops == 0 && !full.isShowing(), "create once without showing");
        TextView content = new TextView(full.getContext()); content.setText("Non-floating dialog content");
        full.setOnShowListener(new android.content.DialogInterface.OnShowListener(){public void onShow(android.content.DialogInterface dialog){shows++;}});
        full.setContentView(content); full.show(); full.show();
        check(starts == 1 && shows == 0, "start synchronous, show listener posted");
        new Handler().postDelayed(new Runnable() { public void run() {
            check(creations == 1 && shows == 1 && full.isShowing(), "show reuses creation");
            check(full.getWindow().getDecorView().getWidth() > 100 && full.getWindow().getDecorView().getHeight() > 100, "nonfloating dialog gets usable allocation");
            full.dismiss(); full.dismiss();
            check(stops == 1, "dismiss once stops once");
            full.show(); check(starts == 2 && creations == 1, "reshow starts without recreating"); full.dismiss();
            try {
                getFilesDir().mkdirs(); java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "results.json"));
                out.write("{\"status\":\"PASS\"}".getBytes("UTF-8")); out.close();
            } catch(java.io.IOException e) { throw new AssertionError(e); }
            System.out.println("DIALOG_CONTRACT PASS");System.exit(0);
        }}, 1500);
    }
}
