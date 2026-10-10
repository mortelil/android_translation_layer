/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;

/**
 * SVC-013c component <b>Ir3</b> — the service re-enters {@code bindService}
 * from inside its own {@code onUnbind}. The FIRST {@code onUnbind} logs and then
 * binds a second, service-owned connection {@code c2} (a distinct Intent action
 * so it is a new binding identity rather than the one being torn down); the flag
 * prevents the second {@code onUnbind} (triggered by the driver's teardown of
 * {@code c2}) from re-binding again, so teardown can complete.
 *
 * The driver tears c2 down via {@link #c2()} (same process), logging it under
 * the client id "c2" to match the spec's planned trace.
 */
public class SvcReenterUnbind extends Service {
    private final IBinder binder = new Binder();

    /** Service-owned second connection used by the re-entrant bind. */
    private static volatile ServiceConnection C2;
    private boolean rebound = false;

    private String component() {
        return new ComponentName(this, SvcReenterUnbind.class).flattenToShortString();
    }

    private static String scen() {
        return EventLog.SVC_013;
    }

    /** The c2 connection created by the re-entrant bind (null until onUnbind). */
    public static ServiceConnection c2() {
        return C2;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEventB(this, scen(), "onCreate", component(),
                -1, -1, "none", "", null, null, null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        EventLog.get().serviceEventB(this, scen(), "onBind", component(),
                -1, -1, "none", "", null, null,
                Integer.toString(System.identityHashCode(binder)));
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boolean ret = false;
        EventLog.get().serviceEventB(this, scen(), "onUnbind", component(),
                -1, -1, "none", "", null, Boolean.toString(ret), null);

        if (!rebound) {
            rebound = true;
            // Log the re-entrant call, then issue it with a distinct identity.
            EventLog.get().serviceEventB(this, scen(), "bindService(c2)", component(),
                    -1, -1, "none", "", null, "called", null);
            try {
                Intent i2 = new Intent();
                i2.setComponent(new ComponentName(this, SvcReenterUnbind.class));
                i2.setAction("org.atl.tests.svc.BIND.REBIND");
                final ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName cn, IBinder service) {
                        EventLog.get().clientEventEx(scen(), "onServiceConnected",
                                cn == null ? "null" : cn.flattenToShortString(),
                                "none", "", "c2",
                                service == null ? null
                                        : Integer.toString(System.identityHashCode(service)),
                                -1, "connected", "IBinder", null);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName cn) {
                        EventLog.get().clientEventEx(scen(), "onServiceDisconnected",
                                cn == null ? "null" : cn.flattenToShortString(),
                                "none", "", "c2", null, -1, "disconnected", "void", null);
                    }
                };
                C2 = conn;
                boolean b = bindService(i2, conn, Context.BIND_AUTO_CREATE);
                EventLog.get().serviceEventB(this, scen(), "bindService(c2)", component(),
                        -1, -1, "none", "", null, Boolean.toString(b), null);
            } catch (Throwable t) {
                EventLog.get().serviceEventB(this, scen(), "bindService(c2)", component(),
                        -1, -1, "none", "", null,
                        "exception:" + t.getClass().getName(), null);
                EventLog.get().noteException("SVC-013c reentrant bindService: " + t);
            }
        }
        return ret;
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEventB(this, scen(), "onDestroy", component(),
                -1, -1, "none", "", null, null, null);
        super.onDestroy();
        EventLog.get().serviceDestroyed(scen(), this);
    }
}
