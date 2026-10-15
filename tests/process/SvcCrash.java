/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** :worker, a hosted callback that throws: the child must exit 10 (P07). */
public class SvcCrash extends ProbeService {
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		ProbeState.marker("ATL_PROBE_SERVICE_THROWS class=" + getClass().getName() + " startId=" + startId);
		throw new IllegalStateException("probe: hosted callback failure");
	}
}
