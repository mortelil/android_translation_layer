// SPDX-License-Identifier: GPL-3.0-only
package org.atl.notificationaction;
import android.app.*;import android.os.Bundle;
public class Probe extends Activity {
 int checks;void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public void onCreate(Bundle state){super.onCreate(state);int result=0;try{
 Notification.Action.Builder builder=new Notification.Action.Builder(0,"test",null);
 check(builder.build().getSemanticAction()==0,"default NONE");
 check(builder.setSemanticAction(1)==builder,"fluent setter");
 Notification.Action first=builder.build();check(first.getSemanticAction()==1,"stored reply");
 builder.setSemanticAction(4);Notification.Action second=builder.build();
 check(second.getSemanticAction()==4,"stored delete");check(first.getSemanticAction()==1,"snapshot isolation");
 check(first!=second,"distinct builds");
 check(builder.setSemanticAction(1234).build().getSemanticAction()==1234,"no runtime enum restriction (Android)");
 check(new Notification.Action.Builder((android.graphics.drawable.Icon)null,"test",null).setSemanticAction(8).build().getSemanticAction()==8,"Icon constructor");
 System.out.println("NOTIFICATION_ACTION_PASS checks="+checks);android.util.Log.i("NotificationActionProbe","NOTIFICATION_ACTION_PASS checks="+checks);
 }catch(Throwable t){result=1;t.printStackTrace();android.util.Log.e("NotificationActionProbe","FAIL "+t);}finish();System.exit(result);}
}
