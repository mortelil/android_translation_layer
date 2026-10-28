// SPDX-License-Identifier: GPL-3.0-only
package org.atl.prepare;

public class Probe extends android.app.Activity {
    public static class App extends android.app.Application {
        static { System.err.println("APP_CODE_RAN static"); }
        public App() { System.err.println("APP_CODE_RAN constructor"); }
        @Override public void onCreate() { System.err.println("APP_CODE_RAN application"); }
    }
    public static class Provider extends android.content.ContentProvider {
        public Provider() { System.err.println("APP_CODE_RAN provider"); }
        @Override public boolean onCreate() { return true; }
        @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri u, String m) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
        @Override public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(android.net.Uri u) { return null; }
        @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { return null; }
        @Override public int delete(android.net.Uri u, String s, String[] a) { return 0; }
        @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { return 0; }
    }
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        System.err.println("PREPARE_LAUNCH_PASS " + Stamp.VALUE);
        System.exit(0);
    }
}
