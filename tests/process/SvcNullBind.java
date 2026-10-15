/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** :second, onBind returns null: a successful binding with a null binder (P06). */
public class SvcNullBind extends ProbeService {
	@Override
	public IBinder onBind(Intent intent) {
		ProbeState.marker("ATL_PROBE_SERVICE_ONBIND class=" + getClass().getName() + " returns=null");
		return null;
	}
}
