/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
/** Regression service: deliberately returns distinct binders per action. */
public class SvcIdentity extends Service {
    static SvcIdentity instance;
    static int destroyed;
    public void onCreate() { instance = this; destroyed = 0; }
    public IBinder onBind(Intent intent) {
        Binder binder = new Binder();
        binder.attachInterface(null, intent.getAction());
        return binder;
    }
    public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }
    public void onDestroy() { destroyed++; }
}
