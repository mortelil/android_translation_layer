// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.values;
import android.os.*;
public class ValuesActivity extends android.app.Activity {
 public void onCreate(Bundle state) {
  super.onCreate(state);
  new Thread(new Runnable(){ public void run(){
   try {
    Looper.prepare(); MessageQueue q=Looper.myQueue(); Handler h=new Handler();
    Runnable noop=new Runnable(){public void run(){}};
    TestValues.check(q.isIdle(),"empty queue");
    h.postDelayed(noop,60000);TestValues.check(q.isIdle(),"future message");
    h.post(noop);TestValues.check(!q.isIdle(),"due message");
    h.removeCallbacks(noop);TestValues.check(q.isIdle(),"removed messages");
    java.lang.reflect.Method post=MessageQueue.class.getDeclaredMethod("enqueueSyncBarrier",long.class);post.setAccessible(true);
    java.lang.reflect.Method remove=MessageQueue.class.getDeclaredMethod("removeSyncBarrier",int.class);remove.setAccessible(true);
    int token=(Integer)post.invoke(q,0L); TestValues.check(!q.isIdle(),"due barrier is non-idle");remove.invoke(q,token);TestValues.check(q.isIdle(),"removed barrier");
    Looper.myLooper().quit(); TestValues.check(q.isIdle(),"empty quit queue");
    TestValues.main(new String[0]);
   } catch(Throwable t) {t.printStackTrace();System.exit(1);}
  }},"value-contracts").start();
 }
}
