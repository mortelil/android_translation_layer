// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
public class FinishLaunch extends android.app.Activity {
    public void onResume() {
        super.onResume();
        finish();
        startActivity(new android.content.Intent(this, Survivor.class));
    }
}
