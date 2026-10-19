// SPDX-License-Identifier: GPL-3.0-only
package org.atl.owner;
public class ParentService extends android.app.Service {
 public android.os.IBinder onBind(android.content.Intent intent){
  android.os.Binder binder=new android.os.Binder();binder.attachInterface(null,"parent="+android.os.Process.myPid());return binder;
 }
}
