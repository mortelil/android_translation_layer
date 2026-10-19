// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
/**
 * Resolves its own authority from its constructor and from onCreate, i.e. from
 * the thread that is installing it. AOSP cannot publish an unfinished record,
 * so the re-entrant lookup must return null instead of waiting for itself
 * (deadlock) - see android.atl.ATLProvider.getContentProvider's re-entrant arm.
 */
public class ReentrantOwner extends ContentProvider {
	private static final Object STATICS = new Object();
	private static int ctors;
	private static int ons;
	static volatile boolean ctorReentrantNull;
	static volatile boolean onCreateReentrantNull;
	private final int token;

	public ReentrantOwner() {
		ctorReentrantNull = ContentProvider.atl_get_content_provider("org.atl.latch.reentrant") == null;
		synchronized (STATICS) { token = ++ctors; }
	}

	public boolean onCreate() {
		onCreateReentrantNull = getContext().getContentResolver().acquireContentProviderClient("org.atl.latch.reentrant") == null;
		synchronized (STATICS) { ons++; }
		return true;
	}

	public Bundle call(String method, String arg, Bundle extras) {
		Bundle b = new Bundle();
		int constructions, creations;
		synchronized (STATICS) { constructions = ctors; creations = ons; }
		b.putBoolean("reentrantCtorNull", ctorReentrantNull);
		b.putBoolean("reentrantOnCreateNull", onCreateReentrantNull);
		b.putInt("token", token);
		b.putInt("ctors", constructions);
		b.putInt("ons", creations);
		b.putBoolean("onDone", true);
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
