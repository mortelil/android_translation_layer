// SPDX-License-Identifier: GPL-3.0-only
package org.atl.cachenative;

import android.app.Activity;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TestActivity extends Activity {
    private static native int answer();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            File cache = getCacheDir();
            File expected = new File(getFilesDir().getParentFile(), "cache");
            if (!cache.getCanonicalFile().equals(expected.getCanonicalFile()))
                throw new AssertionError("Cache is outside the selected app profile: " + cache);
            if (!cache.isDirectory() || !cache.equals(getApplicationContext().getCacheDir()))
                throw new AssertionError("Inconsistent activity/application cache");
            File directory = new File(cache, "extracted/nested");
            if (!directory.mkdirs() && !directory.isDirectory())
                throw new AssertionError("Cannot create extraction directory");
            File library = new File(directory, "libcacheprobe.so");
            InputStream in = getAssets().open("libcacheprobe.so");
            FileOutputStream out = new FileOutputStream(library);
            try {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } finally { out.close(); in.close(); }
            System.load(library.getAbsolutePath());
            if (answer() != 37) throw new AssertionError("Wrong JNI result");
            System.out.println("CACHE_NATIVE_PASS " + cache.getCanonicalPath());
        } catch (Exception e) { throw new RuntimeException(e); }
        finish();
    }
}
