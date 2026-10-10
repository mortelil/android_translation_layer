/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * SVC-013b component <b>Ir2</b> — an ordinary bound service whose client's
 * {@code onServiceConnected} immediately calls {@code unbindService} on itself
 * (the reentrancy lives on the client side, in
 * {@code ControlActivity.Connection}). The service only logs the callbacks; it
 * records whether {@code onUnbind}/{@code onDestroy} follow the client-side
 * unbind issued from inside {@code onServiceConnected}.
 */
public class SvcReenterBind extends Service {
    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcReenterBind.class).flattenToShortString();
    }

    private static String scen() {
        return EventLog.SVC_013;
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
