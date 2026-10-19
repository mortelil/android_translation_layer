// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
/**
 * Constructs fine and then throws in attachInfo: the half-initialised instance
 * exists but must never be published (AOSP publishes only after the install
 * completed, ContentProviderHelper.publishContentProviders:742-808).
 */
public class AttachThrowsOwner extends ContentProvider {
	static final Object STATICS = new Object();
	static int ctors;
	static int attaches;
	public AttachThrowsOwner() { synchronized (STATICS) { ctors++; } }
	public void attachInfo(Context context, ProviderInfo info) {
		synchronized (STATICS) { attaches++; }
		throw new IllegalStateException("probe attachInfo rejection");
	}
	public boolean onCreate() { return true; }
	public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
	public Uri insert(Uri u, ContentValues v) { return null; }
	public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
	public int delete(Uri u, String s, String[] a) { return 0; }
	public String getType(Uri u) { return null; }
	public Bundle call(String method, String arg, Bundle extras) { return null; }
	public ParcelFileDescriptor openFile(Uri u, String m) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
}
