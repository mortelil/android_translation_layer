/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * SVC-002 service under test ("component A"). Logs onCreate /
 * onStartCommand / onDestroy with instance, startId, flags and the delivered
 * intentExtra (extra {@code k}); one instance must serve all three starts with
 * startIds 1,2,3 and each start re-delivered with its own intent.
 */
public class SvcStartIds extends Service {
    private static final String SCENARIO = EventLog.SVC_002;

    private String component() {
        return new ComponentName(this, SvcStartIds.class).flattenToShortString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEvent(this, SCENARIO, "onCreate", component(),
                -1, -1, "none", "", null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String extra = "none";
        if (intent != null && intent.hasExtra("k")) {
            extra = Integer.toString(intent.getIntExtra("k", -1));
        }
        EventLog.get().serviceEvent(this, SCENARIO, "onStartCommand", component(),
                startId, flags, extra, "", Integer.valueOf(START_NOT_STICKY), null);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEvent(this, SCENARIO, "onDestroy", component(),
                -1, -1, "none", "", null, null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
