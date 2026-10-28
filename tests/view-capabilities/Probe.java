// SPDX-License-Identifier: Apache-2.0
package org.atl.viewcapabilities;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.content.pm.PackageManager;
public class Probe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        View a = new View(this), b = new View(this);
        boolean ok = a.isSoundEffectsEnabled() && b.isSoundEffectsEnabled();
        a.setSoundEffectsEnabled(false);
        ok &= !a.isSoundEffectsEnabled() && b.isSoundEffectsEnabled();
        a.setSoundEffectsEnabled(true);
        ok &= a.isSoundEffectsEnabled();
        ok &= !a.isFocusableInTouchMode() && !b.isFocusableInTouchMode();
        a.setFocusable(false);
        a.setFocusableInTouchMode(true);
        ok &= a.isFocusableInTouchMode() && a.isFocusable()
                && a.getFocusable() == View.FOCUSABLE && !b.isFocusableInTouchMode();
        a.setFocusableInTouchMode(false);
        ok &= !a.isFocusableInTouchMode() && a.isFocusable();
        a.setFocusableInTouchMode(true);
        a.setFocusable(View.NOT_FOCUSABLE);
        ok &= !a.isFocusableInTouchMode() && !a.isFocusable();
        a.setFocusableInTouchMode(true);
        a.setFocusable(false);
        ok &= !a.isFocusableInTouchMode() && !a.isFocusable();
        a.setFocusableInTouchMode(true);
        a.setFocusable(View.FOCUSABLE_AUTO);
        ok &= a.isFocusableInTouchMode() && a.getFocusable() == View.FOCUSABLE_AUTO;
        PackageManager pm = getPackageManager();
        boolean distinct = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT);
        boolean multi = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH);
        boolean touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        ok &= (!distinct || multi) && (!multi || touch);
        System.out.println("VIEW_CAPABILITIES " + (ok ? "PASS" : "FAIL"));
        finish();
    }
}
