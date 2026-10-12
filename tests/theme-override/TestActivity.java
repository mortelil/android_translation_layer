// SPDX-License-Identifier: GPL-3.0-only
package org.atl.themeoverride;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ContextThemeWrapper;

public class TestActivity extends Activity {
    static Configuration mode(Context c, int night) {
        Configuration v = new Configuration(c.getResources().getConfiguration());
        v.uiMode = (v.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return v;
    }
    static void expectLocked(ContextThemeWrapper wrapper, Configuration config) {
        try { wrapper.applyOverrideConfiguration(config); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Override accepted after resources or an earlier override");
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int id = getResources().getIdentifier("appearance", "string", getPackageName());
        String original = getString(id);
        Configuration config = mode(this, Configuration.UI_MODE_NIGHT_YES);
        ContextThemeWrapper dark = new ContextThemeWrapper(this, 0);
        dark.applyOverrideConfiguration(config);
        expectLocked(dark, config);
        config.uiMode = Configuration.UI_MODE_NIGHT_NO;
        if (!"dark".equals(dark.getString(id))) throw new AssertionError("Override was not copied/applied");
        if (!original.equals(getString(id))) throw new AssertionError("Override changed parent asset selection");
        ContextThemeWrapper light = new ContextThemeWrapper(this, 0);
        light.applyOverrideConfiguration(mode(this, Configuration.UI_MODE_NIGHT_NO));
        if (!"light".equals(light.getString(id)) || !"dark".equals(dark.getString(id)))
            throw new AssertionError("Sibling resource configurations interfere");
        if (dark.getAssets() != dark.getResources().getAssets()) throw new AssertionError("Assets do not match resources");
        ContextThemeWrapper accessed = new ContextThemeWrapper(this, 0);
        accessed.getResources(); expectLocked(accessed, config);
        accessed = new ContextThemeWrapper(this, 0);
        accessed.getAssets(); expectLocked(accessed, config);
        if (!original.equals(getString(id))) throw new AssertionError("Parent changed");
        System.out.println("THEME_OVERRIDE_PASS copy ordering parent siblings qualifiers");
        finish();
    }
}
