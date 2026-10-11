/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.h;

import android.app.Activity;
import android.os.Bundle;

/**
 * JNI-EXC-001 scenario H launcher Activity.
 *
 * This Activity must NEVER run: the manifest Application class
 * (ScenarioHApplication) throws while it is being constructed, before ATL
 * creates the main Activity. If ATL wrongly continued past that failure, this
 * callback fires and emits SENTINEL-JNI-EXC-001-H, which the analyzer rejects.
 */
public class ScenarioHActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sentinel("onCreate");
        throw new IllegalStateException("JNI-EXC-001-H activity-reached");
    }

    private void sentinel(String where) {
        String line = "SENTINEL-JNI-EXC-001-H " + where;
        System.out.println(line);
        System.out.flush();
        System.err.println(line);
        System.err.flush();
    }
}