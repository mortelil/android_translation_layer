/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * v3 component <b>B</b> — a bound-only Service whose {@code onBind} returns a
 * private binder (one per service instance). Used by SVC-005 (one/multi client),
 * SVC-006 (with/without BIND_AUTO_CREATE) and SVC-007 (started + bound combined).
 *
 * The scenario is not hard-coded: the driver stamps EventLog.currentScenario()
 * before each step (all callbacks run on the main thread), so B can serve three
 * scenarios with one class. onUnbind keeps the default (false).
 */
public class SvcBinder extends Service {
    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcBinder.class).flattenToShortString();
    }

    private static String scen() {
        return EventLog.currentScenario();
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
