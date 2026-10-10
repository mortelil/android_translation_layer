/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * v3 component <b>Bn</b> (SVC-009) — {@code onBind} returns <b>null</b>. This is
 * a valid, non-fatal case: bindService still returns true and the client is
 * delivered {@code onNullBinding} (never {@code onServiceConnected}); the service
 * is created then cleaned up. Whether onUnbind/onDestroy run after the client's
 * unbindService is recorded, not asserted.
 */
public class SvcNullBind extends Service {
    private String component() {
        return new ComponentName(this, SvcNullBind.class).flattenToShortString();
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
                -1, -1, "none", "", null, "null", null);
        return null;
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
