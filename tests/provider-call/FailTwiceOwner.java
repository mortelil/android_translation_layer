// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
/**
 * The first two instances fail in onCreate and every later one succeeds, so a
 * caller has to see: the failed attempts never published, and the next access
 * installs again - one attempt per access, never two at once.
 */
public class FailTwiceOwner extends ContentProvider {
	static final Object STATICS = new Object();
	static int ctors;
	static int ons;
	private final int token;
	private volatile boolean onDone;

	public FailTwiceOwner() { synchronized (STATICS) { token = ++ctors; } }

	public boolean onCreate() {
		int n;
		synchronized (STATICS) { n = ++ons; }
		if (n <= 2) throw new IllegalStateException("probe onCreate rejection " + n);
		onDone = true;
		return true;
	}

	public Bundle call(String method, String arg, Bundle extras) {
		Bundle b = new Bundle();
		synchronized (STATICS) { b.putInt("ctors", ctors); b.putInt("ons", ons); }
		b.putInt("token", token);
		b.putBoolean("onDone", onDone);
		b.putInt("pid", android.os.Process.myPid());
		return b;
	}

	public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
	public Uri insert(Uri u, ContentValues v) { return null; }
	public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
	public int delete(Uri u, String s, String[] a) { return 0; }
	public String getType(Uri u) { return null; }
	public ParcelFileDescriptor openFile(Uri u, String m) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
}
