// SPDX-License-Identifier: GPL-3.0-only
package org.atl.systemuimode;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

public class TestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Resources resources = getResources();
        int system = getApplicationContext().getResources().getConfiguration().uiMode;
        int night = system & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_NO && night != Configuration.UI_MODE_NIGHT_YES)
            throw new AssertionError("System night mode is undefined: " + system);
        if ((system & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_UNDEFINED)
            throw new AssertionError("System UI type is undefined");
        Configuration changed = new Configuration(resources.getConfiguration());
        changed.uiMode = (changed.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night == Configuration.UI_MODE_NIGHT_NO ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        resources.updateConfiguration(changed, null);
        Configuration followSystem = new Configuration(resources.getConfiguration());
        followSystem.uiMode = (followSystem.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        resources.updateConfiguration(followSystem, null);
        if ((resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != night)
            throw new AssertionError("Follow-system mode does not converge in one update");
        System.out.println("SYSTEM_UI_MODE_PASS mode=" + system);
        finish();
    }
}
