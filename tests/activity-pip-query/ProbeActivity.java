// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.pipquery;

public class ProbeActivity extends android.app.Activity {
    private boolean failed;
    private void check(String phase) {
        try {
            if (isInPictureInPictureMode()) throw new AssertionError("normal activity reports PiP in " + phase);
        } catch (Throwable error) {
            failed = true;
            error.printStackTrace();
            System.out.println("PIP_QUERY_FAIL " + phase + " " + error);
        }
    }
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        check("create");
    }
    public void onResume() {
        super.onResume();
        check("resume");
        if (!failed) System.out.println("PIP_QUERY_PASS create,resume");
        finish();
    }
}
