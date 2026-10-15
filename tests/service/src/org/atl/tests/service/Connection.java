/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B): one client-side ServiceConnection with counters
 * and an optional in-callback hook.
 *
 * The hook is what drives the re-entrancy rows: a scenario can issue a
 * bindService() or unbindService() from inside onServiceConnected() (S07) or
 * unbind the connection while its own connect is still in flight (S08). Every
 * callback is logged with the calling thread, which is how S06a proves that the
 * executor overload of bindService() (ContextImpl.bindService(Intent,int,
 * Executor,ServiceConnection), src/api-impl/android/app/ContextImpl.java:377)
 * dispatches off the main looper, not on it.
 */
package org.atl.tests.service;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

final class Connection implements ServiceConnection {

    /** Runs inside the connect callback, before the deliverer continues. */
    interface Hook {
        void onConnected(Connection self, ComponentName name, IBinder binder);
    }

    final String scenario;
    final String name;
    Hook hook;
    ComponentName lastComponent;
    IBinder lastBinder;
    int connects;
    int disconnects;
    int nulls;
    int dieds;

    Connection(String scenario, String name) {
        this.scenario = scenario;
        this.name = name;
    }

    public void onServiceConnected(ComponentName name, IBinder service) {
        connects++;
        this.lastComponent = name;
        this.lastBinder = service;
        EventLog.event(scenario, this.name + ".onServiceConnected",
            "thread=" + Thread.currentThread().getName()
            + " comp=" + EventLog.shortComponent(name)
            + " binder=" + EventLog.idHash(service));
        Hook h = hook;
        if (h != null) {
            h.onConnected(this, name, service);
        }
    }

    public void onServiceDisconnected(ComponentName name) {
        disconnects++;
        EventLog.event(scenario, this.name + ".onServiceDisconnected",
            "comp=" + EventLog.shortComponent(name));
    }

    public void onNullBinding(ComponentName name) {
        nulls++;
        EventLog.event(scenario, this.name + ".onNullBinding",
            "comp=" + EventLog.shortComponent(name));
    }

    public void onBindingDied(ComponentName name) {
        dieds++;
        EventLog.event(scenario, this.name + ".onBindingDied",
            "comp=" + EventLog.shortComponent(name));
    }

    /** Callback counter summary, for verdict messages. */
    String counters() {
        return name + "{connected=" + connects + " null=" + nulls
            + " disconnected=" + disconnects + " died=" + dieds + "}";
    }
}