// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
import java.io.File;
import android.os.Environment;
public class Probe extends android.app.Activity {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            android.view.SurfaceView[] surfaces = {
                new android.view.SurfaceView(this),
                new android.view.SurfaceView(this, null),
                new android.view.SurfaceView(this, null, 0),
                new android.view.SurfaceView(this, null, 0, 0)
            };
            for (android.view.SurfaceView surface : surfaces) {
                check(surface.getHolder() != null);
                check(surface.getHolder().getSurface() != null);
                check(surface.getHolder() == surface.getHolder());
                android.view.SurfaceHolder.Callback callback = new android.view.SurfaceHolder.Callback() {
                    public void surfaceCreated(android.view.SurfaceHolder holder) {}
                    public void surfaceChanged(android.view.SurfaceHolder holder, int f, int w, int h) {}
                    public void surfaceDestroyed(android.view.SurfaceHolder holder) {}
                };
                surface.getHolder().addCallback(callback);
                surface.getHolder().removeCallback(callback);
            }
            android.view.View zView = new android.view.View(this);
            zView.setElevation(2.5f);
            zView.setZ(3.25f);
            check(zView.getTranslationZ() == .75f && zView.getZ() == 3.25f);
            zView.setTranslationZ(-3f);
            check(zView.getZ() == -.5f && zView.getElevation() == 2.5f);
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
            java.lang.reflect.Method selected = cls.getDeclaredMethod("setSelectedDirectory", String.class);
            selected.setAccessible(true);
            selected.invoke(provider, new Object[] { null });
            android.net.Uri uri = android.net.Uri.parse("content://media/external/images/media/0");
            android.database.Cursor cursor = provider.query(uri, new String[]{"_id", "date_modified", "_size"}, null, null, null);
            check(cursor.getCount() == 0); cursor.close();
            check(provider.getType(uri) == null);
            try { provider.openFile(uri, "r"); throw new AssertionError("opened cancelled selection"); }
            catch (java.io.FileNotFoundException expected) {}
            System.out.println("SMALL_COMPAT PASS");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(); System.out.println("SMALL_COMPAT FAIL"); System.exit(1);
        }
    }
}
