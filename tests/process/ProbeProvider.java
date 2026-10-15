/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

/**
 * Content providers of the probe package. WorkerProvider is declared with
 * android:process=":worker", so only the :worker child may create it; the
 * loader must create only MainProvider. Which process created which provider is
 * exactly the process match of ContentProvider.createContentProviders()
 * (P05/P10).
 */
abstract class ProbeProvider extends ContentProvider {
	abstract String authority();

	@Override
	public boolean onCreate() {
		ProbeState.providerOnCreate = true;
		ProbeState.providersCreated++;
		ProbeState.marker("ATL_PROBE_PROVIDER_ONCREATE authority=" + authority());
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		return null;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		return 0;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		return 0;
	}

	@Override
	public String getType(Uri uri) {
		return null;
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) {
		return null;
	}
}
