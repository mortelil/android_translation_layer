// SPDX-License-Identifier: GPL-3.0-only
package org.atl.displayqueries;
public class Probe extends android.app.Activity {
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        int failures = 0;
        try {
            if (android.os.Build.SOC_MANUFACTURER == null || android.os.Build.SOC_MODEL == null)
                throw new AssertionError("null SoC metadata");
        } catch (Throwable t) { t.printStackTrace(); failures++; }
        try {
            if (getWindowManager().getDefaultDisplay().getPixelFormat() != android.graphics.PixelFormat.RGBA_8888)
                throw new AssertionError("legacy pixel format");
        } catch (Throwable t) { t.printStackTrace(); failures++; }
        try {
            if (!new android.view.View(this).hasOverlappingRendering())
                throw new AssertionError("default overlap");
            android.view.View nonOverlapping = new android.view.View(this) {
                @Override public boolean hasOverlappingRendering() { return false; }
            };
            if (nonOverlapping.hasOverlappingRendering()) throw new AssertionError("override ignored");
        } catch (Throwable t) { t.printStackTrace(); failures++; }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "results.json"))) {
            out.write(("{\"failures\":" + failures + "}").getBytes("UTF-8"));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        System.out.println("DISPLAY_QUERIES failures=" + failures);
    }
}
