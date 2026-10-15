/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** :worker, second service of the same process (same pid, P09). */
public class SvcWorker2 extends ProbeService {}
