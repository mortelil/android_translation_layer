/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * SVC-015 service under test ("component A"). N=5 started cycles plus N=5 bound
 * cycles, then a final state probe.
 *
 * IMPORTANT instance indexing: SVC-015's planned trace requires the instance
 * index to <b>increment</b> across cycles (fresh {@code onCreate} each cycle,
 * {@code startId} restarting at 1 per newly created instance). The shared
 * {@code EventLog.serviceDestroyed(...)} reset is therefore deliberately NOT
 * called here, so the per-scenario identity map keeps increasing (1,2,3,…)
 * instead of re-numbering each fresh instance to 1. Each instance still gets one
 * {@code onDestroy}; only the index numbering differs from the v3/v4 components.
 *
 * A fresh private {@code Binder} per instance makes the bound cycles' binder
 * identity hashes distinct (binder reuse is comparable within the process).
 */
public class SvcCycles extends Service {
    private static final String SCENARIO = EventLog.SVC_015;

    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcCycles.class).flattenToShortString();
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
        boolean ret = false;
        EventLog.get().serviceEventB(this, SCENARIO, "onUnbind", component(),
                -1, -1, "none", "", null, Boolean.toString(ret), null);
        return ret;
    }

    @Override
    public void onDestroy() {
        EventLog.get().serviceEventB(this, SCENARIO, "onDestroy", component(),
                -1, -1, "none", "", null, null, null);
        super.onDestroy();
        // NOTE: no serviceDestroyed() here (see class doc): SVC-015 wants a
        // monotonically increasing instance index across cycles.
    }
}
