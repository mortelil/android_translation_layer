/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * v4 component <b>B</b> for SVC-010 (duplicate bindings / invalid unbind /
 * Intent binding identity) and SVC-011 (rapid start/stop/bind/unbind bursts).
 *
 * Unlike the v3 {@link SvcBinder} it also logs {@code onStartCommand}, because
 * SVC-011 burst A/C observe the started-side callback interleaving. The scenario
 * is stamped by the driver (all callbacks run on the main thread), so one class
 * serves both scenarios. onUnbind keeps the default (false) so the last unbind
 * really tears the instance down.
 */
public class SvcV4B extends Service {
    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcV4B.class).flattenToShortString();
    }

    private static String scen() {
        return EventLog.currentScenario();
    }

    /** Human-readable intent identity summary for the event log (no addresses). */
    private static String intentExtra(Intent intent) {
        if (intent == null) {
            return "none";
        }
        if (intent.hasExtra("x")) {
            return "x=" + intent.getIntExtra("x", -1);
        }
        if (intent.hasExtra("cmd")) {
            String v = intent.getStringExtra("cmd");
            return "cmd=" + v;
        }
        return "none";
    }

    private static String cmdOf(Intent intent) {
        if (intent == null || !intent.hasExtra("cmd")) {
            return "";
        }
        String v = intent.getStringExtra("cmd");
        return (v == null) ? "" : v;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEventB(this, scen(), "onCreate", component(),
                -1, -1, "none", "", null, null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        EventLog.get().serviceEventB(this, scen(), "onStartCommand", component(),
                startId, flags, intentExtra(intent), cmdOf(intent), null, null, null);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        EventLog.get().serviceEventB(this, scen(), "onBind", component(),
                -1, -1, intentExtra(intent), cmdOf(intent), null, null,
                Integer.toString(System.identityHashCode(binder)));
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boolean ret = false;
        EventLog.get().serviceEventB(this, scen(), "onUnbind", component(),
                -1, -1, intentExtra(intent), cmdOf(intent), null, Boolean.toString(ret), null);
        return ret;
    }

    @Override
    public void onRebind(Intent intent) {
        EventLog.get().serviceEventB(this, scen(), "onRebind", component(),
                -1, -1, intentExtra(intent), cmdOf(intent), null, null, null);
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEventB(this, scen(), "onDestroy", component(),
                -1, -1, "none", "", null, null, null);
        super.onDestroy();
        EventLog.get().serviceDestroyed(scen(), this);
    }
}
