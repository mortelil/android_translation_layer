// SPDX-License-Identifier: GPL-3.0-only
package org.atl.captioninglocale;
import android.app.Activity;
import android.os.Bundle;
import android.view.accessibility.CaptioningManager;
import java.util.Locale;
public class Probe extends Activity {
 public void onCreate(Bundle state) {
  super.onCreate(state);
  Locale original=Locale.getDefault();
  int result=0;
  try {
   CaptioningManager manager=(CaptioningManager)getSystemService("captioning");
   if(manager==null)throw new AssertionError("missing captioning service");
   if(manager.getLocale()!=null)throw new AssertionError("test requires unset caption locale");
   Locale.setDefault(Locale.JAPAN);
   if(manager.getLocale()!=null)throw new AssertionError("device locale is not a caption preference");
   System.out.println("CAPTION_LOCALE_PASS checks=3");
   android.util.Log.i("CaptionLocaleProbe","CAPTION_LOCALE_PASS checks=3");
  } catch(Throwable t) { result=1;t.printStackTrace();android.util.Log.e("CaptionLocaleProbe","FAIL "+t); }
  finally { Locale.setDefault(original); }
  finish();System.exit(result);
 }
}
