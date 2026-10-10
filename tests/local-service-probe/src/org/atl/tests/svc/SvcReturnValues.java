/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * SVC-003 service under test ("component A"). Each intent's extra {@code want}
 * selects the value returned from onStartCommand (2=START_NOT_STICKY,
 * 3=START_REDELIVER_INTENT, absent=START_STICKY). The service logs the
 * returnValue; the probe only observes it and must never treat a START_* value
 * as a stop refcount (onDestroy only after the explicit stopService).
 */
public class SvcReturnValues extends Service {
    private static final String SCENARIO = EventLog.SVC_003;

    private String component() {
        return new ComponentName(this, SvcReturnValues.class).flattenToShortString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.get().serviceEvent(this, SCENARIO, "onCreate", component(),
                -1, -1, "none", "", null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int want = (intent == null) ? -1 : intent.getIntExtra("want", -1);
        int ret;
        if (want == START_NOT_STICKY) {
            ret = START_NOT_STICKY;
        } else if (want == START_REDELIVER_INTENT) {
            ret = START_REDELIVER_INTENT;
        } else {
            ret = START_STICKY;
        }
        EventLog.get().serviceEvent(this, SCENARIO, "onStartCommand", component(),
                startId, flags, "none", "", Integer.valueOf(ret), null);
        return ret;
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
