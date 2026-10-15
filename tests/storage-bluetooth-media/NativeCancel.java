// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
public class NativeCancel extends android.app.Activity {
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        new Thread(new Runnable() { public void run() {
            try {
                android.content.ContentProvider provider = (android.content.ContentProvider)
                    Class.forName("android.atl.ATLMediaContentProvider").newInstance();
                android.database.Cursor cursor = provider.query(
                    android.net.Uri.parse("content://media/external/images/media/123"),
                    new String[]{"_id", "_size"}, null, null, null);
                java.lang.reflect.Field field = provider.getClass().getDeclaredField("selectedFile");
                field.setAccessible(true);
                if (cursor.getCount() != 0) throw new AssertionError("cancel must return no rows: selected=" + field.get(provider));
                cursor.close();
                System.out.println("NATIVE_CANCEL PASS"); System.exit(0);
            } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
        }}).start();
    }
}
