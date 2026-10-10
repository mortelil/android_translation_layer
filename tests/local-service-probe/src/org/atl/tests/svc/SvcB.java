/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * Second DECLARED service ("declared-but-different component"). SVC-001 does
 * not start it; it exists so later scenarios (start IDs, binding, stopSelf)
 * can target a distinct component without rewiring the manifest, and it proves
 * the unknown-component case is genuinely distinct from this declared one.
 * Its callbacks are logged identically to {@link SvcA}.
 */
public class SvcB extends Service {

    private String component() {
        return new ComponentName(this, SvcB.class).flattenToShortString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEvent(this, "onCreate", component(), -1, -1, "none");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        EventLog.get().serviceEvent(this, "onStartCommand", component(),
                startId, flags, "none");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEvent(this, "onDestroy", component(), -1, -1, "none");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
