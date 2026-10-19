// SPDX-License-Identifier: GPL-3.0-only
package org.atl.editorenter;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.EditText;
public class Probe extends Activity {
 public void onCreate(Bundle state) {
  super.onCreate(state);
  EditText field = new EditText(this);
  field.setText("Press Enter");
  field.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
   public boolean onEditorAction(android.widget.TextView view, int action, KeyEvent event) {
   if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER
       || event.getAction() != KeyEvent.ACTION_DOWN || action != 0) {
    android.util.Log.e("EditorEnter", "FAIL action=" + action + " event=" + event);
    System.exit(1);
   }
   android.util.Log.i("EditorEnter", "EDITOR_ENTER_PASS key=66 action=0 editor=0");
   System.out.println("EDITOR_ENTER_PASS key=66 action=0 editor=0");
   finish(); System.exit(0); return true;
  }});
  setContentView(field); field.requestFocus();
  android.util.Log.i("EditorEnter", "READY");
 }
}
