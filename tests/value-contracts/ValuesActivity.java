// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.values;
import android.os.*;
public class ValuesActivity extends android.app.Activity {
 static class CacheView extends android.view.View {
  int destroyed;
  CacheView(android.content.Context c){super(c);}
  public void destroyDrawingCache(){destroyed++;super.destroyDrawingCache();}
 }
 public void onCreate(Bundle state) {
  super.onCreate(state);
  CacheView v=new CacheView(this);
  TestValues.check(v.getAccessibilityTraversalBefore()==android.view.View.NO_ID,"default before");
  TestValues.check(v.getAccessibilityTraversalAfter()==android.view.View.NO_ID,"default after");
  v.setAccessibilityTraversalBefore(123); v.setAccessibilityTraversalAfter(456);
  TestValues.check(v.getAccessibilityTraversalBefore()==123&&v.getAccessibilityTraversalAfter()==456,"independent traversal ids");
  v.setAccessibilityTraversalBefore(android.view.View.NO_ID);TestValues.check(v.getAccessibilityTraversalBefore()==android.view.View.NO_ID,"clear traversal");
  TestValues.check(!v.willNotCacheDrawing(),"default cache flag");
  int destroyed=v.destroyed;v.setWillNotCacheDrawing(true);TestValues.check(v.willNotCacheDrawing()&&v.destroyed==destroyed+1,"flag invalidates cache");
  v.setWillNotCacheDrawing(true);TestValues.check(v.destroyed==destroyed+1,"unchanged flag preserves cache");
  TestValues.check(v.getDrawingCache()==null&&v.getDrawingCache(true)==null,"disabled cache");
  v.setWillNotCacheDrawing(false);TestValues.check(!v.willNotCacheDrawing()&&v.destroyed==destroyed+2,"re-enable invalidates cache");
  TestValues.check(!new android.view.autofill.AutofillManager().hasEnabledAutofillServices(),"no autofill capability");
  TestValues.check(!android.appwidget.AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported(),"no widget pinning capability");
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
