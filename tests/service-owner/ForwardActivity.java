// SPDX-License-Identifier: GPL-3.0-only
package org.atl.owner;
import android.content.*;
import android.os.*;
public class ForwardActivity extends android.app.Activity {
 public void onCreate(Bundle state) {
  super.onCreate(state);
  final Handler h=new Handler();
  h.postDelayed(new Runnable(){public void run(){throw new AssertionError("forward timeout");}},15000);
  if(!bindService(new Intent(this,ForwardService.class),new ServiceConnection(){
   public void onServiceConnected(ComponentName name,final IBinder binder){
    final ServiceConnection self=this;
    h.post(new Runnable(){public void run(){
     String desc=binder.getInterfaceDescriptor();
     if(desc.equals("pending")){h.postDelayed(this,100);return;}
     if(!desc.equals("forward:parent="+android.os.Process.myPid()))throw new AssertionError(desc);
     unbindService(self);System.out.println("CHILD_TO_PARENT_PASS");System.out.flush();System.exit(0);
    }});
   }
   public void onServiceDisconnected(ComponentName n){throw new AssertionError("unexpected disconnect");}
  },Context.BIND_AUTO_CREATE))throw new AssertionError("child bind rejected");
 }
}
