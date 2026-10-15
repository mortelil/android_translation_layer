/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

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
 *
 * <p>Missing callbacks are only a NEGATIVE signal, so the binder also answers
 * {@link #TRANSACTION_IDENTITY} with the identity of the process that really
 * serves the placement (its pid and process name). The client records that reply,
 * and {@code verify_unsupported_remote.py} requires the serving pid to differ
 * from the caller's pid: a placement served inside the caller's own process
 * produces the same API returns and no remote callbacks, and is still rejected.
 */
public class SvcRemote extends Service {
    private static final String SCENARIO = EventLog.SVC_014;

    /** Transaction code answered by the binder below (client: ControlActivity). */
    static final int TRANSACTION_IDENTITY = 1;

    private final IBinder binder = new IdentityBinder();

    /**
     * Answers the identity transaction with THIS process' pid and process name;
     * every callback runs wherever the instance really lives, so the reply names
     * the serving process, not the caller.
     */
    private static final class IdentityBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TRANSACTION_IDENTITY) {
                return super.onTransact(code, data, reply, flags);
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(android.os.Process.myPid());
                reply.writeString(processName());
            }
            return true;
        }
    }

    /**
     * The running process' name ({@code Application.getProcessName()}: the real
     * {@code :remote} name on Android, the spawned manifest process in an ATL
     * child). Best effort: a runtime without the API reports "unknown" rather
     * than failing the caller — the pid is what proves the separation.
     */
    static String processName() {
        try {
            String name = android.app.Application.getProcessName();
            return name == null ? "unknown" : name;
        } catch (Throwable t) {
            return "unknown";
        }
    }

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
