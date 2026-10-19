package org.atl.tests.remaining;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;

public class RemainingActivity extends Activity {
 static int checks;
 static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
 interface Action { void run(); }
 static void rejects(Class<?> type, Action action) {
  checks++;
  try { action.run(); } catch (Throwable t) { if (type.isInstance(t)) return; throw new AssertionError(t); }
  throw new AssertionError("Expected " + type);
 }
 static class GuardView extends View {
  boolean ready;
  GuardView(Context c) { super(c); ready=true; }
  public boolean isEnabled() { if (!ready) throw new AssertionError("premature enabled getter"); return super.isEnabled(); }
  public boolean isSelected() { if (!ready) throw new AssertionError("premature selected getter"); return super.isSelected(); }
  public boolean isActivated() { if (!ready) throw new AssertionError("premature activated getter"); return super.isActivated(); }
 }
 public void onCreate(Bundle b) {
  super.onCreate(b);
  try {
   ContextWrapper wrapper = new ContextWrapper(this);
   check(getPackageName().equals(wrapper.getBasePackageName()), "base package delegation");
   check(getPackageName().equals(wrapper.getOpPackageName()), "op package delegation");
   GuardView v = new GuardView(this); v.setSelected(true);
   check(v.getDrawableState().length>0,"drawable state after construction");
   FrameMetrics m = new FrameMetrics();
   m.mTimingData[0]=1; m.mTimingData[2]=100; m.mTimingData[9]=160; m.mTimingData[18]=140;
   check(m.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==1,"first frame flag");
   check(m.getMetric(FrameMetrics.DEADLINE)==60,"deadline distinct from duration");
   check(m.getMetric(FrameMetrics.TOTAL_DURATION)==40,"total duration");
   m.mTimingData[0]=2; check(m.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0,"unrelated flag");
   FrameMetrics copy=new FrameMetrics(m); m.mTimingData[2]=0;
   check(copy.getMetric(FrameMetrics.TOTAL_DURATION)==40,"frame copy owns timing data");
   check(m.getMetric(-1)==-1 && m.getMetric(15)==-1,"unknown metrics");
   Window.OnFrameMetricsAvailableListener l = new Window.OnFrameMetricsAvailableListener() {
    public void onFrameMetricsAvailable(Window w,FrameMetrics metrics,int dropped) { throw new AssertionError("invented metrics"); }
   };
   rejects(NullPointerException.class, new Action(){ public void run(){ getWindow().addOnFrameMetricsAvailableListener(null,null); }});
   getWindow().addOnFrameMetricsAvailableListener(l,null); getWindow().removeOnFrameMetricsAvailableListener(l);
   NetworkStatsManager stats=(NetworkStatsManager)getSystemService(Context.NETWORK_STATS_SERVICE);
   check(stats!=null,"netstats service exists");
   check(stats.querySummaryForDevice(1,null,0,1)==null,"unavailable stats not zero usage");
   rejects(IllegalArgumentException.class, new Action(){ public void run(){ stats.queryDetails(99,null,0,1); }});
   rejects(NullPointerException.class, new Action(){ public void run(){ stats.registerUsageCallback(1,null,1,null); }});
   NetworkStatsManager.UsageCallback cb = new NetworkStatsManager.UsageCallback(){ public void onThresholdReached(int n,String id){} };
   rejects(UnsupportedOperationException.class, new Action(){ public void run(){ stats.registerUsageCallback(1,null,1,cb); }});
   rejects(IllegalArgumentException.class, new Action(){ public void run(){ stats.unregisterUsageCallback(cb); }});
   ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
   check(process.importance==100 && process.processState==6,"process defaults");
   check(new ActivityManager.RunningAppProcessInfo("test",123,new String[]{"p"}).uid==0,"caller-built info has no inferred uid");
   for (String dir : Environment.STANDARD_DIRECTORIES) {
    File f=Environment.getExternalStoragePublicDirectory(dir);
    check(f.isDirectory(),"public directory "+dir);
    File test=new File(f,"synthetic-test"); try(FileOutputStream out=new FileOutputStream(test)) { out.write(42); }
    check(test.delete(),"writable public directory");
   }
   android.service.notification.NotificationListenerService listener = new android.service.notification.NotificationListenerService() {};
   check(listener.onBind(new Intent())==null,"no notification bridge");
   check(listener.getActiveNotifications().length==0,"unconnected notifications");
   System.out.println("REMAINING_PASS checks="+checks);
   finish(); System.exit(0);
  } catch(Throwable t) { t.printStackTrace(); finish(); System.exit(1); }
 }
}
