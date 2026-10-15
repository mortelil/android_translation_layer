// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
public class FolderProbe extends android.app.Activity {
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        new Thread(new Runnable() { public void run() {
            try {
                android.content.ContentProvider provider = (android.content.ContentProvider)
                    Class.forName("android.atl.ATLMediaContentProvider").newInstance();
                android.net.Uri audio = android.net.Uri.parse("content://media/external/audio/media");
                android.database.Cursor c = provider.query(audio, new String[]{"_id", "title", "duration"}, null, null, null);
                if (c.getCount() != 1 || !c.moveToFirst() || !"Test Song".equals(c.getString(1)) || c.getLong(2) != 1000)
                    throw new AssertionError("Selected folder did not expose tagged synthetic music: rows=" + c.getCount() + " root=" + getSharedPreferences("atl-media", 0).getString("root", null));
                c.close();
                System.out.println("FOLDER_PROBE PASS"); System.exit(0);
            } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
        }}).start();
    }
}
