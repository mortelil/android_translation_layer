// SPDX-License-Identifier: Apache-2.0
package org.atl.activityalias;
public class Starter extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        startActivity(new android.content.Intent("org.atl.activityalias.OPEN").setPackage(getPackageName()));
        new android.os.Handler().postDelayed(new Runnable() {
            public void run() { System.out.println("ACTIVITY_ALIAS FAIL timeout"); System.exit(1); }
        }, 5000);
    }
}
