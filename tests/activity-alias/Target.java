// SPDX-License-Identifier: Apache-2.0
package org.atl.activityalias;
import android.content.*;
public class Target extends android.app.Activity {
    static int creations;
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (++creations != 1 || !"org.atl.activityalias.OPEN".equals(getIntent().getAction())) {
            System.out.println("ACTIVITY_ALIAS FAIL wrong target or duplicate"); System.exit(1);
        }
        new android.os.Handler().postDelayed(new Runnable() {
            public void run() {
                startActivity(new Intent().setComponent(new ComponentName(getPackageName(), getPackageName()+".Alias"))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        }, 100);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (creations != 1 || !(getPackageName()+".Alias").equals(intent.getComponent().getClassName())) {
            System.out.println("ACTIVITY_ALIAS FAIL intent identity"); System.exit(1);
        }
        System.out.println("ACTIVITY_ALIAS PASS"); System.exit(0);
    }
}
