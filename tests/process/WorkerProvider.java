/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

/** Declared in android:process=":worker". */
public class WorkerProvider extends ProbeProvider {
	@Override
	String authority() {
		return "org.atl.procprobe.worker";
	}
}
