// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
import java.io.File;
import android.os.Environment;
public class Probe extends android.app.Activity {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            File root = Environment.getExternalStorageDirectory();
            check(Environment.isExternalStorageEmulated(root));
            check(Environment.isExternalStorageEmulated());
            check(Environment.isExternalStorageEmulated(new File(root, "new/subdir")));
            check(!Environment.isExternalStorageRemovable(root));
            check(!Environment.isExternalStorageRemovable());
            for (File bad : new File[] {root.getParentFile(), new File(root + "-sibling"), new File(root, "../outside")}) {
                try { Environment.isExternalStorageEmulated(bad); throw new AssertionError("accepted " + bad); }
                catch (IllegalArgumentException expected) {}
            }
            try { Environment.isExternalStorageEmulated(null); throw new AssertionError("accepted null"); }
            catch (NullPointerException expected) {}
            check(android.bluetooth.BluetoothAdapter.getDefaultAdapter() == null);
            android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager)getSystemService("bluetooth");
            check(manager != null && manager.getAdapter() == null);
            check(android.bluetooth.BluetoothAdapter.class.getMethod("isEnabled").getReturnType() == boolean.class);
            // Exercise the legacy provider without ATL_MEDIA_ROOT or a user chooser.
            Class<?> cls = Class.forName("android.atl.ATLMediaContentProvider");
            android.content.ContentProvider provider = (android.content.ContentProvider)cls.newInstance();
            java.lang.reflect.Method selected = cls.getDeclaredMethod("setSelectedFile", String.class);
            selected.setAccessible(true);
            selected.invoke(provider, new Object[] { null });
            android.net.Uri uri = android.net.Uri.parse("content://media/external/images/media/0");
            android.database.Cursor cursor = provider.query(uri, new String[]{"_id", "date_modified", "_size"}, null, null, null);
            check(cursor.getCount() == 0); cursor.close();
            check(provider.getType(uri) == null);
            try { provider.openFile(uri, "r"); throw new AssertionError("opened cancelled selection"); }
            catch (java.io.FileNotFoundException expected) {}
            File file = new File(getFilesDir(), "probe.unknownextension");
            new java.io.FileOutputStream(file).close();
            selected.invoke(provider, file.getAbsolutePath());
            check(provider.getType(uri) != null);
            cursor = provider.query(uri, new String[]{"_id", "date_modified", "_size"}, null, null, null);
            check(cursor.getCount() == 1); cursor.close();
            provider.openFile(uri, "r").close();
            selected.invoke(provider, new Object[] { null });
            check(provider.getType(uri) == null);
            System.out.println("SMALL_COMPAT PASS");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(); System.out.println("SMALL_COMPAT FAIL"); System.exit(1);
        }
    }
}
