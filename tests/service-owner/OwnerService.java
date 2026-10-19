// SPDX-License-Identifier: GPL-3.0-only
package org.atl.owner;
public class OwnerService extends android.app.Service {
    public android.os.IBinder onBind(android.content.Intent intent) {
        android.os.Binder binder = new android.os.Binder();
        binder.attachInterface(null,"owner="+getPackageName()+";process="+android.app.Application.getProcessName()+";pid="+android.os.Process.myPid());
        return binder;
    }
}
