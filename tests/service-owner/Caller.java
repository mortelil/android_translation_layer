// SPDX-License-Identifier: GPL-3.0-only
package org.atl.ownerprobe;
import android.app.Activity;
import android.content.*;
import android.os.*;
public class Caller extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() { public void run() { throw new AssertionError("bind timeout"); } },15000);
        Intent intent = new Intent().setComponent(new ComponentName("org.atl.owner","org.atl.owner.OwnerService"));
        if (!bindService(intent,new ServiceConnection() {
            public void onServiceConnected(ComponentName component, IBinder service) {
                try {
                    String descriptor=service.getInterfaceDescriptor();
                    if(!descriptor.startsWith("owner=org.atl.owner;process=com.custom.worker;pid=") || descriptor.endsWith("pid="+android.os.Process.myPid()))
                        throw new AssertionError(descriptor);
                    unbindService(this);
                    System.out.println("CROSS_PACKAGE_SERVICE_PASS "+descriptor);
                    System.out.flush(); System.exit(0);
                } catch (RuntimeException e) { throw new RuntimeException(e); }
            }
            public void onServiceDisconnected(ComponentName name) { throw new AssertionError("unexpected disconnect"); }
        },Context.BIND_AUTO_CREATE)) throw new AssertionError("bind rejected");
    }
}
