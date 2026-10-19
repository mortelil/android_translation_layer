// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
/** onCreate always throws: no half-created provider may become visible. */
public class OnCreateThrowsOwner extends ContentProvider {
	public boolean onCreate() { throw new IllegalStateException("probe onCreate rejection"); }
	public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
	public Uri insert(Uri u, ContentValues v) { return null; }
	public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
	public int delete(Uri u, String s, String[] a) { return 0; }
	public String getType(Uri u) { return null; }
	public Bundle call(String method, String arg, Bundle extras) { return null; }
	public ParcelFileDescriptor openFile(Uri u, String m) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
}
