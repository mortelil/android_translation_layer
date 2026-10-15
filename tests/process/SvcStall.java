/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** :stallable, stalled before READY by the child-side fault seam. */
public class SvcStall extends ProbeService {}
