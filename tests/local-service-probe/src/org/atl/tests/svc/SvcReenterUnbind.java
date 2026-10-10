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
 *
 * BOUNDED REDESIGN (probe v5, SVC-013c replacement): the guard is a
 * <b>persistent (static) field</b>, not a per-instance field. V4's per-instance
 * {@code rebound} flag was reset by every newly created instance because the
 * platform destroyed the instance before the re-entrant {@code bindService} was
 * delivered, so the guard never latched and the process cycled unboundedly
 * (onCreate/onBind/onUnbind/bindService(c2)/onDestroy) until the 120s watchdog.
 * With the static guard the re-entrant {@code bindService(c2)} is issued AT MOST
 * ONCE for the whole scenario, so the run terminates and the ordering of that
 * reentrant bind relative to {@code onDestroy} becomes observable.
 */
public class SvcReenterUnbind extends Service {
    private final IBinder binder = new Binder();

    /** Service-owned second connection used by the re-entrant bind. */
    private static volatile ServiceConnection C2;
    /**
     * Persistent (process-wide) guard: the re-entrant bind is issued at most
     * once for the scenario, so a replacement instance created after the first
     * onUnbind cannot re-trigger it (v4 cycled here). Static, not per-instance.
     */
    private static boolean REBOUND = false;

    /** Reset the persistent guard before the scenario runs (deterministic rerun). */
    static void resetGuard() {
        REBOUND = false;
        C2 = null;
    }

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

        if (!REBOUND) {
            REBOUND = true;
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
