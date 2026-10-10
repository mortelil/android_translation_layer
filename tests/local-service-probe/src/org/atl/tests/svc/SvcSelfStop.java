/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * SVC-004 service under test ("component A"). The intent extra {@code cmd}
 * drives a self-stop from inside onStartCommand:
 *   none        — no self-stop
 *   stopOld     — stopSelfResult(extra oldId): an OLD start id must return
 *                 false and NOT stop the live instance
 *   stopSelfNow — stopSelf(): stops the current service; onDestroy must follow
 *                 after onStartCommand returns (never re-entrant inside it)
 * The service logs the startId, the call and its returned boolean.
 */
public class SvcSelfStop extends Service {
    private static final String SCENARIO = EventLog.SVC_004;

    private String component() {
        return new ComponentName(this, SvcSelfStop.class).flattenToShortString();
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

        if ("stopOld".equals(cmd)) {
            int oldId = (intent == null) ? -1 : intent.getIntExtra("oldId", -1);
            boolean b = stopSelfResult(oldId);
            EventLog.get().serviceEvent(this, SCENARIO, "stopSelfResult", component(),
                    oldId, flags, "none", cmd, null, Boolean.toString(b));
        } else if ("stopSelfNow".equals(cmd)) {
            EventLog.get().serviceEvent(this, SCENARIO, "stopSelf", component(),
                    startId, flags, "none", cmd, null, "called");
            stopSelf();
        }

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
