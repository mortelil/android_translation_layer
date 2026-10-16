// SPDX-License-Identifier: GPL-3.0-only
// Manual visual regression screen: compare equal logical window sizes at 1/1.5/2x.
import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
public class DensityActivity extends Activity {
    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    LinearLayout root;
    void row(View view) { root.addView(view, new LinearLayout.LayoutParams(-1, dp(56))); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16)); root.setBackgroundColor(Color.WHITE);
        TextView heading = new TextView(this); heading.setText("Density comparison"); heading.setTextSize(20); row(heading);
        TextView text = new TextView(this); text.setText("Text 16sp and bitmap 24dp"); text.setTextSize(16);
        text.setCompoundDrawablesWithIntrinsicBounds(getResources().getDrawable(getResources().getIdentifier("density_icon", "drawable", getPackageName())), null, null, null); row(text);
        EditText input = new EditText(this); input.setTextSize(16); input.setText("Editable text"); row(input);
        CheckBox check = new CheckBox(this); check.setText("Check box"); check.setChecked(true); row(check);
        Switch toggle = new Switch(this); toggle.setText("Switch"); toggle.setChecked(true); row(toggle);
        SeekBar seek = new SeekBar(this); seek.setMax(100); seek.setProgress(50); row(seek);
        final Button popup = new Button(this); popup.setText("Open popup"); row(popup);
        popup.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            TextView body = new TextView(DensityActivity.this); body.setText("Popup 16sp"); body.setTextSize(16); body.setBackgroundColor(0xffeeeeee);
            final PopupWindow window = new PopupWindow(body, dp(240), dp(80), true);
            body.setOnClickListener(new View.OnClickListener() { public void onClick(View w) { System.out.println("DENSITY_UI popup clicked"); window.dismiss(); } });
            window.showAsDropDown(popup);
        } });
        Button dialog = new Button(this); dialog.setText("Open dialog"); row(dialog);
        dialog.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { new AlertDialog.Builder(DensityActivity.this).setTitle("Dialog")
            .setMessage("Text should keep its logical size.").setPositiveButton("OK", null).show(); } });
        setContentView(root);
    }
}
