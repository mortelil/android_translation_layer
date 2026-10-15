/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Services of the probe package, one class per manifest declaration (a child
 * resolves a service by class name). Every instance lifecycle transition is
 * printed with the owning pid, so a child's inherited stdout shows what ran in
 * which process.
 */
abstract class ProbeService extends Service {
	@Override
	public void onCreate() {
		super.onCreate();
		ProbeState.marker("ATL_PROBE_SERVICE_ONCREATE class=" + getClass().getName());
	}

	@Override
	public void onDestroy() {
		ProbeState.marker("ATL_PROBE_SERVICE_ONDESTROY class=" + getClass().getName());
		super.onDestroy();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		ProbeState.marker("ATL_PROBE_SERVICE_ONSTART class=" + getClass().getName() + " startId=" + startId
		    + " action=" + (intent == null ? null : intent.getAction())
		    + " extra=" + (intent == null ? null : intent.getStringExtra("probe"))
		    + " category=" + (intent == null ? null : intent.getCategories()));
		/* START_STICKY; echoed back through the START reply. */
		return 1;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		ProbeState.marker("ATL_PROBE_SERVICE_ONUNBIND class=" + getClass().getName());
		return true;
	}

	@Override
	public IBinder onBind(Intent intent) {
		ProbeState.marker("ATL_PROBE_SERVICE_ONBIND class=" + getClass().getName());
		return new Binder();
	}
}
