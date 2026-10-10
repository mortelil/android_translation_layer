/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * SVC-017 component <b>Bts</b> — {@code onUnbind} returns <b>true</b> and the
 * service implements {@code onRebind}. Unlike the v3 {@link SvcUnbindTrue}
 * (SVC-008b, bound-only) this instance is <b>started first</b>, so it stays alive
 * after the last unbind and the rebind path is reachable while the SAME instance
 * remains alive. A dedicated class is used so SVC-017 can never observe a
 * leftover instance from SVC-008b.
 *
 * Logs onCreate/onStartCommand/onBind/onUnbind/onRebind/onDestroy with the
 * scenario stamped by the driver (all callbacks run on the main thread).
 */
public class SvcUnbindTrueStarted extends Service {
    private static final String SCENARIO = EventLog.SVC_017;

    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcUnbindTrueStarted.class).flattenToShortString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEventB(this, SCENARIO, "onCreate", component(),
                -1, -1, "none", "", null, null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        EventLog.get().serviceEventB(this, SCENARIO, "onStartCommand", component(),
                startId, flags, "none", "", null, null, null);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        EventLog.get().serviceEventB(this, SCENARIO, "onBind", component(),
                -1, -1, "none", "", null, null,
                Integer.toString(System.identityHashCode(binder)));
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boolean ret = true;
        EventLog.get().serviceEventB(this, SCENARIO, "onUnbind", component(),
                -1, -1, "none", "", null, Boolean.toString(ret), null);
        return ret;
    }

    @Override
    public void onRebind(Intent intent) {
        EventLog.get().serviceEventB(this, SCENARIO, "onRebind", component(),
                -1, -1, "none", "", null, null, null);
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEventB(this, SCENARIO, "onDestroy", component(),
                -1, -1, "none", "", null, null, null);
        super.onDestroy();
        EventLog.get().serviceDestroyed(SCENARIO, this);
    }
}
