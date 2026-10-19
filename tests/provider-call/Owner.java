// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
public class Owner extends ContentProvider {
    public static final class BadParcel implements Parcelable {
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel out, int flags) {
            out.writeStrongBinder(new Binder());
            throw new IllegalArgumentException("intentional parcel failure");
        }
        public static final Parcelable.Creator<BadParcel> CREATOR = new Parcelable.Creator<BadParcel>() {
            public BadParcel createFromParcel(Parcel in) { return new BadParcel(); }
            public BadParcel[] newArray(int n) { return new BadParcel[n]; }
        };
    }
    private int calls;
    public boolean onCreate() { return true; }
    public Bundle call(String method, String arg, Bundle extras) {
        if ("badparcel".equals(method)) { Bundle bad=new Bundle();bad.putParcelable("bad",new BadParcel());return bad; }
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
        if (u.toString().endsWith("/utfbytes")) {
            char[] chars = new char[15000]; java.util.Arrays.fill(chars, '\u263a');
            String text = new String(chars);
            android.database.MatrixCursor wide = new android.database.MatrixCursor(new String[]{"a","b","c"});
            wide.addRow(new Object[]{text,text,text}); return wide;
        }
        if (u.toString().endsWith("/widecolumns")) {
            char[] chars = new char[65000]; java.util.Arrays.fill(chars, 'x');
            return new android.database.MatrixCursor(new String[]{new String(chars)});
        }
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
