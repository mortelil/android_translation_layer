/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.singletask;
import android.app.Activity;
import android.content.Intent;
import android.os.*;
public class Probe extends Activity {
    static int creates;
    int resumes, starts, pauses, intents;
    boolean returned;
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public void onCreate(Bundle b){super.onCreate(b);check(++creates==1,"singleTask recreated");setContentView(new android.widget.TextView(this));}
    public void onStart(){super.onStart();starts++;}
    public void onPause(){pauses++;super.onPause();}
    public void onNewIntent(Intent i){super.onNewIntent(i);check(returned,"synchronous delivery");check(pauses==1,"intent delivered before pause");check(i.getIntExtra("test",0)==42,"intent extras lost");check(creates==1,"wrong instance");intents++;}
    public void onResume(){super.onResume();resumes++;
        if(resumes==1){new Handler().post(new Runnable(){public void run(){getApplicationContext().startActivity(new Intent(Probe.this,Probe.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("test",42));returned=true;}});}
        else {new Handler().post(new Runnable(){public void run(){try{
            check(intents==1 && resumes==2 && starts==1,"duplicate or missing lifecycle");
            ((android.media.AudioManager)getSystemService(AUDIO_SERVICE)).setParameters("atl_test_unknown=1");
            getFilesDir().mkdirs();java.io.FileWriter w=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"));w.write("{\"status\":\"PASS\"}");w.close();System.out.println("SINGLE_TASK PASS");
        }catch(Exception e){throw new RuntimeException(e);}}});}
    }
}
