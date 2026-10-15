/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B): the service under test.
 *
 * Every lifecycle callback the probe asserts on is recorded as one event tagged
 * `<SimpleClassName>.<callback>`, so a row's service-side sequence is the exact
 * list of what production actually delivered (not a stub count). The row tag is
 * per component, so a FAIL names the component that diverged.
 *
 * onStartCommand() returns START_NOT_STICKY: the local gate never restarts a
 * service, and the restart policy of a crashed record is lane-B row S12 (the
 * fake gate), not this one.
 */
package org.atl.tests.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

public abstract class ProbeService extends Service {

    // Test-only crash request: refuse an in-process caller before scheduling exit.
    static final int CRASH_TRANSACTION = 0x43525348;
    private final IBinder binder = new Binder() {
        @Override protected boolean onTransact(int code, android.os.Parcel data,
                                               android.os.Parcel reply, int flags) {
            if (code != CRASH_TRANSACTION) return super.onTransact(code, data, reply, flags);
            int caller = data.readInt();
            if (caller != Binder.getCallingPid() || caller == android.os.Process.myPid())
                throw new SecurityException("crash probe requires a different caller process");
            reply.writeNoException();
            reply.writeInt(android.os.Process.myPid());
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { System.exit(23); }
            }, 100);
            return true;
        }
    };

    /** Acceptance row this component's callbacks are logged under. */
    protected abstract String scenario();

    /** Event tag: the simple class name, so one log line names its component. */
    protected final String tag() {
        return EventLog.simpleName(getClass().getName());
    }

    /** onBind() result: the shared Binder, or null for the S04 null-binding component. */
    protected boolean provideBinder() {
        return true;
    }

    /** onUnbind() result: false, or true for the S04 rebind component. */
    protected boolean unbindResult() {
        return false;
    }

    /**
     * onStartCommand() result: START_NOT_STICKY by default - the local gate never
     * restarts a service. Rows S10/S13 need a restartable policy, so they override
     * this instead of the callback.
     */
    protected int startResult() {
        return START_NOT_STICKY;
    }

    /** Per-row command hook, driven by the intent extra "cmd". */
    protected void onCommand(String cmd, Intent intent, int startId) {
    }

    /** Records a service-side event under this component's tag. */
    protected final void note(String what, String detail) {
        EventLog.event(scenario(), tag() + "." + what, detail);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.event(scenario(), tag() + ".onCreate", "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent == null ? null : intent.getStringExtra("cmd");
        EventLog.event(scenario(), tag() + ".onStartCommand",
            "id=" + startId + " flags=" + flags + " cmd=" + (cmd == null ? "none" : cmd));
        onCommand(cmd, intent, startId);
        return startResult();
    }

    @Override
    public IBinder onBind(Intent intent) {
        EventLog.event(scenario(), tag() + ".onBind", "action=" + action(intent));
        return provideBinder() ? binder : null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boolean result = unbindResult();
        EventLog.event(scenario(), tag() + ".onUnbind",
            "action=" + action(intent) + " ret=" + result);
        return result;
    }

    @Override
    public void onRebind(Intent intent) {
        EventLog.event(scenario(), tag() + ".onRebind", "action=" + action(intent));
    }

    @Override
    public void onDestroy() {
        EventLog.event(scenario(), tag() + ".onDestroy", "");
        super.onDestroy();
    }

    private static String action(Intent intent) {
        return intent == null || intent.getAction() == null ? "none" : intent.getAction();
    }
}