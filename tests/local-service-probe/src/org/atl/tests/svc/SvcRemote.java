/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * SVC-014b component, declared in the manifest with
 * {@code android:process=":remote"}. On real Android the platform runs this
 * service in a SEPARATE process, so the control Activity's in-process event log
 * shows no service callbacks; only the client-side API returns matter.
 *
 * Every callback logs through a null-safe {@link EventLog#get()} lookup: in a
 * genuinely separate process the singleton is uninitialised (null) and logging
 * is skipped (never an NPE / crash of the remote process). If ATL instead
 * instantiates this in the SAME process, {@code get()} is non-null and the
 * callbacks DO appear in the control log — which is exactly the explicit
 * evidence that ATL did not spawn a second process (the documented-limitation
 * case the spec calls out), never silently masked.
 */
public class SvcRemote extends Service {
    private static final String SCENARIO = EventLog.SVC_014;

    private final IBinder binder = new Binder();

    private String component() {
        return new ComponentName(this, SvcRemote.class).flattenToShortString();
    }

    private static EventLog logOrNull() {
        try {
            return EventLog.get();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog log = logOrNull();
        if (log != null) {
            log.serviceEventB(this, SCENARIO, "onCreate", component(),
                    -1, -1, "none", "", null, null, null);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        EventLog log = logOrNull();
        if (log != null) {
            log.serviceEventB(this, SCENARIO, "onStartCommand", component(),
                    startId, flags, "none", "", null, null, null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        EventLog log = logOrNull();
        if (log != null) {
            log.serviceEventB(this, SCENARIO, "onBind", component(),
                    -1, -1, "none", "", null, null,
                    Integer.toString(System.identityHashCode(binder)));
        }
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        EventLog log = logOrNull();
        if (log != null) {
            log.serviceEventB(this, SCENARIO, "onUnbind", component(),
                    -1, -1, "none", "", null, Boolean.toString(false), null);
        }
        return false;
    }

    @Override
    public void onDestroy() {
        EventLog log = logOrNull();
        if (log != null) {
            log.serviceEventB(this, SCENARIO, "onDestroy", component(),
                    -1, -1, "none", "", null, null, null);
        }
        super.onDestroy();
    }
}
