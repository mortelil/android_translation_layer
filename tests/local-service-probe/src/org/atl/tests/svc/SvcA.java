/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * The service under test for SVC-001. Its lifecycle callbacks are logged with
 * instance (identity keyed), startId, flags and thread via {@link EventLog}.
 */
public class SvcA extends Service {

    private String component() {
        return new ComponentName(this, SvcA.class).flattenToShortString();
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
        // Not sticky: the probe drives every start explicitly.
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
