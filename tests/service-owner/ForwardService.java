// SPDX-License-Identifier: GPL-3.0-only
package org.atl.owner;
import android.content.*;
import android.os.*;
public class ForwardService extends android.app.Service {
 final Binder binder=new Binder();
 public void onCreate(){
  super.onCreate();binder.attachInterface(null,"pending");
  if(!bindService(new Intent(this,ParentService.class),new ServiceConnection(){
   public void onServiceConnected(ComponentName n,IBinder parent){
    String desc=parent.getInterfaceDescriptor();
    if(desc.equals("parent="+android.os.Process.myPid()))throw new AssertionError("parent service ran in child");
    unbindService(this);binder.attachInterface(null,"forward:"+desc);
   }
   public void onServiceDisconnected(ComponentName n){throw new AssertionError("parent disconnected");}
  },Context.BIND_AUTO_CREATE))throw new AssertionError("forward bind rejected");
 }
 public IBinder onBind(Intent intent){return binder;}
}
