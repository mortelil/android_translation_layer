/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S06b: a Service that binds SvcLeakTarget
 * from its own context in onCreate().
 *
 * The whole point is the leak guard: the registration belongs to this instance's
 * ContextImpl, so when production destroys this instance it must also unbind
 * that registration and tear SvcLeakTarget down (ATLServiceController
 * unbindInstanceContextClients, the LoadedApk.removeContextRegistrations
 * analogue). SvcLeakTarget's onUnbind()/onDestroy() - and the later
 * "Service not registered" IllegalArgumentException for LEAKY_CONNECTION - are
 * the observable evidence; nothing here is a stand-in for production work.
 */
package org.atl.tests.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

public class SvcLeaky extends ProbeService {

    /** The connection this service issues from its own context; the probe unbinds it later to prove it is gone. */
    public static final ServiceConnection LEAKY_CONNECTION = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            EventLog.event("S06", "leak.onServiceConnected", "comp=" + EventLog.shortComponent(name));
        }

        public void onServiceDisconnected(ComponentName name) {
            EventLog.event("S06", "leak.onServiceDisconnected", "comp=" + EventLog.shortComponent(name));
        }

        public void onNullBinding(ComponentName name) {
            EventLog.event("S06", "leak.onNullBinding", "comp=" + EventLog.shortComponent(name));
        }

        public void onBindingDied(ComponentName name) {
            EventLog.event("S06", "leak.onBindingDied", "comp=" + EventLog.shortComponent(name));
        }
    };

    protected String scenario() {
        return "S06";
    }

    @Override
    protected boolean provideBinder() {
        // Nobody binds this component: it exists to issue the leaked registration.
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Intent target = new Intent(this, SvcLeakTarget.class);
        boolean bound = bindService(target, LEAKY_CONNECTION, BIND_AUTO_CREATE);
        EventLog.event("S06", "SvcLeaky.bindOwnContext",
            "comp=" + EventLog.shortComponent(target.getComponent()) + " ret=" + bound);
    }
}