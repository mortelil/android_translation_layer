/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * SVC-013a component <b>Ir</b> — the service re-enters its own start path from
 * inside {@code onStartCommand}: when the delivered intent carries
 * {@code cmd=reenter} it logs the nested start, then calls {@code startService}
 * on itself with {@code cmd=none}. The nested call must not run recursively
 * inside the current callback; the event order records whether the extra
 * {@code onStartCommand} (with a new startId) is queued after this one returns.
 */
public class SvcReenterStart extends Service {
    private String component() {
        return new ComponentName(this, SvcReenterStart.class).flattenToShortString();
    }

    private static String scen() {
        return EventLog.SVC_013;
    }

    private static String cmdOf(Intent intent) {
        if (intent == null || !intent.hasExtra("cmd")) {
            return "none";
        }
        String v = intent.getStringExtra("cmd");
        return (v == null) ? "none" : v;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEvent(this, scen(), "onCreate", component(),
                -1, -1, "none", "", null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = cmdOf(intent);
        EventLog.get().serviceEvent(this, scen(), "onStartCommand", component(),
                startId, flags, "none", cmd, null, null);

        if ("reenter".equals(cmd)) {
            // Log the re-entrant call itself, then issue it with cmd=none.
            EventLog.get().serviceEvent(this, scen(), "startService(reenter)", component(),
                    startId, flags, "none", cmd, null, "called");
            try {
                Intent again = new Intent();
                again.setComponent(new ComponentName(this, SvcReenterStart.class));
                again.setAction("org.atl.tests.svc.START");
                again.putExtra("cmd", "none");
                ComponentName cn = startService(again);
                EventLog.get().serviceEvent(this, scen(), "startService(reenter)",
                        component(), startId, flags, "none", cmd,
                        null, (cn == null) ? "null" : cn.flattenToShortString());
            } catch (Throwable t) {
                EventLog.get().serviceEvent(this, scen(), "startService(reenter)",
                        component(), startId, flags, "none", cmd, null,
                        "exception:" + t.getClass().getName());
                EventLog.get().noteException("SVC-013a nested startService: " + t);
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEvent(this, scen(), "onDestroy", component(),
                -1, -1, "none", "", null, null);
        super.onDestroy();
        EventLog.get().serviceDestroyed(scen(), this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
