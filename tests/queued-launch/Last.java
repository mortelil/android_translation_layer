/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.queuedlaunch;
public class Last extends android.app.Activity {
    private boolean reported;
    public void onCreate(android.os.Bundle b){
        super.onCreate(b);
        Probe.check(Probe.childPostReturned,"nested target entered inside onPostCreate");
        Probe.check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper(),"target not on UI thread");
        Probe.lastCreated=true;
        android.widget.TextView text=new android.widget.TextView(this);text.setText("FINAL ACTIVITY");setContentView(text);
    }
    public void onResume(){
        super.onResume();if(reported)return;reported=true;
        new android.os.Handler().postDelayed(new Runnable(){public void run(){
            try{
                getFilesDir().mkdirs();
                java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"));
                out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));out.close();
                System.out.println("QUEUED_LAUNCH PASS");
            }catch(Exception e){throw new RuntimeException(e);}
        }},500);
    }
}
