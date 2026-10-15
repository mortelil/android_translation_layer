/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** :isolated, isolatedProcess: must be refused, never hosted. */
public class SvcIsolated extends ProbeService {}
