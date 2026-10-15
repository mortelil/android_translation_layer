// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
public class Survivor extends android.app.Activity {
    public void onResume() {
        super.onResume();
        new android.os.Handler().postDelayed(new Runnable() { public void run() {
            System.out.println("FINISH_LAUNCH PASS");
            finish();
        }}, 1500);
    }
}
