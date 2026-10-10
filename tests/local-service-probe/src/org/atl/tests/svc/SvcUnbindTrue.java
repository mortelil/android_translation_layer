/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * v3 component <b>Bt</b> (SVC-008b) — onBind returns a private binder and
 * {@code onUnbind} returns <b>true</b>, so the instance survives the last unbind
 * (no onDestroy) and a subsequent bind takes the {@code onRebind} path instead
 * of onCreate/onBind.
 */
public class SvcUnbindTrue extends Service {
    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcUnbindTrue.class).flattenToShortString();
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
        boolean ret = true;
        EventLog.get().serviceEventB(this, scen(), "onUnbind", component(),
                -1, -1, "none", "", null, Boolean.toString(ret), null);
        return ret;
    }

    @Override
    public void onRebind(Intent intent) {
        EventLog.get().serviceEventB(this, scen(), "onRebind", component(),
                -1, -1, "none", "", null, null, null);
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEventB(this, scen(), "onDestroy", component(),
                -1, -1, "none", "", null, null, null);
        super.onDestroy();
        EventLog.get().serviceDestroyed(scen(), this);
    }
}
