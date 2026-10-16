// SPDX-License-Identifier: GPL-3.0-only
package org.atl.mobile.smoke;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

public class TestProviderCall {
    public static class Provider extends ContentProvider {
        private int calls;
        public boolean onCreate() { return true; }
        public Bundle call(String method, String arg, Bundle extras) {
            if ("reject".equals(method)) throw new IllegalArgumentException("unsupported operation");
            if (!"echo".equals(method)) return super.call(method, arg, extras);
            Bundle result = new Bundle();
            result.putString("arg", arg);
            result.putInt("value", extras.getInt("value") + ++calls);
            return result;
        }
        public Cursor query(Uri u,String[] p,String s,String[] a,String o) { return null; }
        public Uri insert(Uri u,ContentValues v) { return null; }
        public int update(Uri u,ContentValues v,String s,String[] a) { return 0; }
        public int delete(Uri u,String s,String[] a) { return 0; }
        public String getType(Uri u) { return null; }
        public ParcelFileDescriptor openFile(Uri u,String m) throws java.io.FileNotFoundException {
            throw new java.io.FileNotFoundException();
        }
    }
    public static void run(Context context) throws Exception {
        ContentProviderClient client = context.getContentResolver().acquireContentProviderClient("org.atl.smoke.calls");
        if (client == null) throw new AssertionError("provider lookup");
        Bundle extras = new Bundle(); extras.putInt("value", 41);
        Bundle result = client.call("echo", "argument", extras);
        if (result.getInt("value") != 42 || !"argument".equals(result.getString("arg")))
            throw new AssertionError("provider did not receive call arguments");
        if (client.call("unknown", null, null) != null) throw new AssertionError("default result");
        try { client.call("reject", null, null); throw new AssertionError("exception lost"); }
        catch (IllegalArgumentException expected) {}
        try { client.call(null, null, null); throw new AssertionError("null method accepted"); }
        catch (NullPointerException expected) {}
        client.release();
        System.out.println("PASS: local provider call dispatch, arguments, default and exceptions");
    }
}
