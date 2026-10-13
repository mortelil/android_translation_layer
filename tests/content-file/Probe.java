/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.contentfile;

import android.content.*;
import android.net.Uri;
import android.os.*;
import java.io.*;

public class Probe extends android.app.Activity {
    private static int calls;
    private static boolean receivedSignal;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static class Provider extends ContentProvider {
        public boolean onCreate() { return true; }
        public android.database.Cursor query(Uri u, String[] p, String s, String[] a, String sort) { return null; }
        public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
        public String getType(Uri u) { return "application/octet-stream"; }
        public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
            calls++;
            return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), "fixture"), ParcelFileDescriptor.parseMode(mode));
        }
        public android.content.res.AssetFileDescriptor openAssetFile(Uri u, String mode, CancellationSignal signal) throws FileNotFoundException {
            return new android.content.res.AssetFileDescriptor(openFile(u, mode, signal), 0, -1);
        }
        public android.content.res.AssetFileDescriptor openTypedAssetFile(Uri u, String mime, Bundle options, CancellationSignal signal) throws FileNotFoundException {
            return openAssetFile(u, "r", signal);
        }
        public ParcelFileDescriptor openFile(Uri u, String mode, CancellationSignal signal) throws FileNotFoundException {
            receivedSignal = signal != null;
            if (signal != null) signal.throwIfCanceled();
            if ("/cancel".equals(u.getPath())) {
                signal.cancel();
                signal.throwIfCanceled();
            }
            return super.openFile(u, mode, signal);
        }
    }
    private static int read(ParcelFileDescriptor fd) throws IOException {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) { return in.read(); }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getFilesDir().mkdirs();
            File f = new File(getFilesDir(), "fixture");
            try (OutputStream out = new FileOutputStream(f)) { out.write(73); }
            ContentResolver r = getContentResolver();
            check(read(r.openFile(Uri.fromFile(f), "r", null)) == 73, "file bytes");
            Uri content = Uri.parse("content://org.atl.contentfile.provider/fixture");
            check(read(r.openFile(content, "r", new CancellationSignal())) == 73, "provider bytes");
            check(receivedSignal, "signal forwarded to provider");
            int before = calls;
            CancellationSignal canceled = new CancellationSignal(); canceled.cancel();
            try { r.openFile(content, "r", canceled); throw new AssertionError("pre-canceled opened"); }
            catch (OperationCanceledException expected) { }
            check(calls == before, "no provider open after pre-cancel");
            try { r.openFile(Uri.parse("content://org.atl.contentfile.provider/cancel"), "r", new CancellationSignal()); throw new AssertionError("provider cancel ignored"); }
            catch (OperationCanceledException expected) { }
            try { r.openFile(Uri.parse("content://org.atl.missing/fixture"), "r", null); throw new AssertionError("missing provider accepted"); }
            catch (FileNotFoundException expected) { }
            try { r.openFile(Uri.fromFile(f), "invalid", null); throw new AssertionError("invalid mode accepted"); }
            catch (IllegalArgumentException expected) { }
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(r.openFile(content, "wt", null))) { out.write(42); }
            check(f.length() == 1 && read(r.openFile(content, "r", null)) == 42, "write/truncate real file");
            try (OutputStream out = new FileOutputStream(new File(getFilesDir(), "results.json"))) { out.write("{\"status\":\"PASS\"}".getBytes("UTF-8")); }
            System.out.println("CONTENT_FILE PASS");
        } catch (Throwable t) { t.printStackTrace(); System.out.println("CONTENT_FILE FAIL " + t); }
    }
}
