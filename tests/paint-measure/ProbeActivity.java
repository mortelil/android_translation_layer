// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.paintmeasure;
public class ProbeActivity extends android.app.Activity {
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try { TestPaintMeasure.run(); }
        catch (Throwable error) { error.printStackTrace(); System.out.println("PAINT_MEASURE_FAIL " + error); }
        finish();
    }
}
