// SPDX-License-Identifier: GPL-3.0-only
package org.atl.ttsunavailable;
import android.app.Activity;
import android.os.*;
import android.speech.tts.TextToSpeech;
public class Probe extends Activity {
 int calls;boolean constructed;
 void require(boolean ok){if(!ok)throw new AssertionError("TTS unavailable contract");}
 public void onCreate(Bundle b){super.onCreate(b);
  TextToSpeech.OnInitListener listener=new TextToSpeech.OnInitListener(){public void onInit(int status){require(constructed);require(status==TextToSpeech.ERROR);require(Looper.myLooper()==Looper.getMainLooper());calls++;}};
  new TextToSpeech(this,listener);new TextToSpeech(this,listener,"synthetic.missing.engine");new TextToSpeech(this,null,"synthetic.missing.engine");constructed=true;
  new Handler().postDelayed(new Runnable(){public void run(){require(calls==2);System.out.println("TTS_UNAVAILABLE_PASS");finish();System.exit(0);}},200);
 }
}
