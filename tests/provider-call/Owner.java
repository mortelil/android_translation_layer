// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
public class Owner extends ContentProvider {
    private int calls;
    public boolean onCreate() { return true; }
    public Bundle call(String method, String arg, Bundle extras) {
        if ("die".equals(method)) { System.exit(23); return null; }
        if ("throw".equals(method)) throw new IllegalArgumentException("probe rejection");
        Bundle b = new Bundle();
        b.putString("owner", getContext().getPackageName());
        b.putString("caller", getCallingPackage());
        android.app.ActivityManager manager = (android.app.ActivityManager)getContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses())
            if (process.pid == Binder.getCallingPid()) b.putString("callerProcessPackage", process.pkgList[0]);
        b.putString("arg", arg);
        b.putInt("value", extras == null ? -1 : extras.getInt("value"));
        b.putInt("pid", android.os.Process.myPid());
        b.putInt("callerPid", Binder.getCallingPid());
        b.putInt("calls", ++calls);
        b.putParcelable("intent", extras == null ? null : extras.getParcelable("intent"));
        b.putParcelableArray("accounts", new android.accounts.Account[0]);
        return b;
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o) {
        android.database.MatrixCursor cursor = new android.database.MatrixCursor(new String[]{"text","number","real","empty","blob"});
        int count = u.toString().endsWith("/large") ? 4097 : 1;
        for (int i=0;i<count;i++) cursor.addRow(new Object[]{getCallingPackage(), 12345678901L, 1.25, null, new byte[]{1,2,3}});
        return cursor;
    }
    public Uri insert(Uri u,ContentValues v) { return null; }
    public int update(Uri u,ContentValues v,String s,String[] a) { return 0; }
    public int delete(Uri u,String s,String[] a) { return 0; }
    public String getType(Uri u) { return null; }
    public ParcelFileDescriptor openFile(Uri u,String m) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
}
