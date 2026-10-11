/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * SVC-016 service under test ("component A"). The intent extra {@code cmd}
 * drives a self-stop from inside {@code onStartCommand} using the CURRENT start
 * id (the half not covered by SVC-004, which only exercised an OLD id via
 * stopSelfResult plus a plain stopSelf):
 *   none                 — no self-stop
 *   stopSelfResultCurrent — stopSelfResult(startId): current id → expect true
 *   stopSelfIntOld        — stopSelf(oldId extra): an OLD id must NOT stop
 *   stopSelfIntCurrent    — stopSelf(startId): current id stops
 * The service logs every call and its returned boolean / "called" marker; a
 * fresh instance after a self-stop is re-numbered instance=1 by EventLog.
 */
public class SvcStopSelfV4 extends Service {
    private static final String SCENARIO = EventLog.SVC_016;

    private String component() {
        return new ComponentName(this, SvcStopSelfV4.class).flattenToShortString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEvent(this, SCENARIO, "onCreate", component(),
                -1, -1, "none", "", null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = "none";
        if (intent != null && intent.hasExtra("cmd")) {
            String v = intent.getStringExtra("cmd");
            if (v != null) {
                cmd = v;
            }
        }
        EventLog.get().serviceEvent(this, SCENARIO, "onStartCommand", component(),
                startId, flags, "none", cmd, null, null);

        if ("stopSelfResultCurrent".equals(cmd)) {
            boolean b = stopSelfResult(startId);
            EventLog.get().serviceEvent(this, SCENARIO, "stopSelfResult", component(),
                    startId, flags, "none", cmd, null, Boolean.toString(b));
        } else if ("stopSelfIntOld".equals(cmd)) {
            int oldId = (intent == null) ? -1 : intent.getIntExtra("oldId", -1);
            EventLog.get().serviceEvent(this, SCENARIO, "stopSelf", component(),
                    oldId, flags, "none", cmd, null, "called");
            stopSelf(oldId);
        } else if ("stopSelfIntCurrent".equals(cmd)) {
            EventLog.get().serviceEvent(this, SCENARIO, "stopSelf", component(),
                    startId, flags, "none", cmd, null, "called");
            stopSelf(startId);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEvent(this, SCENARIO, "onDestroy", component(),
                -1, -1, "none", "", null, null);
        super.onDestroy();
        EventLog.get().serviceDestroyed(SCENARIO, this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
