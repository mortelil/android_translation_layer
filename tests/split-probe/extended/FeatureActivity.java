// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.feature;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.ApplicationInfo;
import java.util.zip.ZipFile;
public class FeatureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean passed = false;
        try {
            ApplicationInfo info = getApplicationInfo();
            if (!info.packageName.equals("org.atl.tests.feature")) throw new AssertionError("package");
            if (info.splitSourceDirs == null || info.splitSourceDirs.length != 1) throw new AssertionError("splits");
            try (ZipFile base = new ZipFile(info.sourceDir); ZipFile feature = new ZipFile(info.splitSourceDirs[0])) {
                if (base.getEntry("classes.dex") != null) throw new AssertionError("base chosen by dex");
                if (feature.getEntry("classes.dex") == null) throw new AssertionError("feature dex missing");
            }
            if (Build.VERSION.SDK_INT < 24) throw new AssertionError("container minSdk lost");
            try (java.io.InputStream in = getAssets().open("feature.txt")) {
                if (in.read() != 42) throw new AssertionError("feature asset");
            }
            passed = true;
            System.out.println("FEATURE_CONTRACT_PASS sdk=" + Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FEATURE_CONTRACT_FAIL " + t);
        }
        // ATL currently requires explicit creation of the files directory.
        getFilesDir().mkdirs();
        try (java.io.FileOutputStream out = openFileOutput("results.json", MODE_PRIVATE)) {
            out.write(("{\"status\":\"" + (passed ? "OK" : "FAIL") + "\",\"sdk\":" + Build.VERSION.SDK_INT + "}").getBytes("UTF-8"));
        } catch (Exception e) { throw new RuntimeException(e); }
        System.out.println("FEATURE_CONTRACT_DONE");
    }
}
