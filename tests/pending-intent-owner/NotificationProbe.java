package org.atl.pendingprobe;
import android.app.*;import android.content.*;import android.os.*;
public class NotificationProbe extends Activity {
 static {System.loadLibrary("pendingactions");}
 static native int actions(boolean activate);
 Handler h=new Handler();int delivered;
 void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 void fail(Throwable t){t.printStackTrace();System.out.println("NOTIFICATION_TOKEN_FAIL "+t);System.exit(1);}
 public void onCreate(Bundle b){super.onCreate(b);try{
  registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){check("token".equals(i.getStringExtra("value")),"correct token payload");delivered++;}},new IntentFilter("org.atl.pendingprobe.NOTIFICATION"));
  final NotificationManager manager=(NotificationManager)getSystemService("notification");
  final PendingIntent token=PendingIntent.getBroadcast(this,90,new Intent("org.atl.pendingprobe.NOTIFICATION").setPackage(getPackageName()).putExtra("value","token"),PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this).setContentTitle("Synthetic token test").setContentIntent(token).build();
  manager.notify(90,n);check(actions(false)==1,"one desktop capability");
  manager.notify(90,n);check(actions(false)==1,"replacement releases previous action");
  actions(true);
  h.postDelayed(new Runnable(){public void run(){try{
   check(delivered==1,"desktop activation dispatches token");token.cancel();actions(true);
   h.postDelayed(new Runnable(){public void run(){try{
    check(delivered==1,"canceled token ignored");manager.cancelAll();check(actions(false)==0,"cancelAll releases actions");
    System.out.println("NOTIFICATION_TOKEN_PASS");finish();System.exit(0);
   }catch(Throwable t){fail(t);}}},100);
  }catch(Throwable t){fail(t);}}},100);
 }catch(Throwable t){fail(t);}}
}
