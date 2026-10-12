/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.remoteservices;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;

public class TestProvider extends ContentProvider {
    static volatile int marker, calls, closes;
    public boolean onCreate() { return true; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        Bundle b = new Bundle();
        b.putString("android:query-arg-sql-selection", selection);
        b.putStringArray("android:query-arg-sql-selection-args", args);
        b.putString("android:query-arg-sql-sort-order", order);
        return query(uri, projection, b, null);
    }
    public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal signal) {
        calls++;
        String path = uri.getPath();
        if ("/error".equals(path)) throw new IllegalArgumentException("provider-test-error");
        if ("/null".equals(path)) return null;
        if ("/empty".equals(path)) return new MatrixCursor(new String[]{"empty"});
        if ("/oversize".equals(path)) {
            MatrixCursor c = cursor(new String[]{"large"}); c.addRow(new Object[]{new byte[70000]}); return c;
        }
        if ("/rows".equals(path)) {
            MatrixCursor c = cursor(new String[]{"many"});
            for (int i=0;i<513;i++) c.addRow(new Object[]{i}); return c;
        }
        if (!"/values".equals(path)) throw new IllegalArgumentException("unexpected path");
        if (projection == null || !"requested".equals(projection[0]) || args == null ||
            !"filter=?".equals(args.getString("android:query-arg-sql-selection")) ||
            !"argument".equals(args.getStringArray("android:query-arg-sql-selection-args")[0]) ||
            !"sort".equals(args.getString("android:query-arg-sql-sort-order")))
            throw new IllegalArgumentException("query arguments were lost");
        MatrixCursor c = cursor(new String[]{"pid","marker","caller","null","long","float","string","blob"});
        c.addRow(new Object[]{android.os.Process.myPid(), marker, Binder.getCallingPid(), null, 1234567890123L, 2.5, "æ漢字", new byte[]{0,1,-1}});
        Bundle extras = new Bundle(); extras.putString("test", "extras"); c.setExtras(extras);
        return c;
    }
    private MatrixCursor cursor(String[] columns) {
        return new MatrixCursor(columns) { public void close() { closes++; super.close(); } };
    }
    public String getType(Uri uri) { return "vnd.atl.test"; }
    public Uri insert(Uri uri, ContentValues v) { throw new AssertionError("unexpected mutation"); }
    public int update(Uri uri, ContentValues v, String s, String[] a) { throw new AssertionError("unexpected mutation"); }
    public int delete(Uri uri, String s, String[] a) { throw new AssertionError("unexpected mutation"); }
    public ParcelFileDescriptor openFile(Uri uri, String mode) { throw new AssertionError("unexpected file access"); }
}
