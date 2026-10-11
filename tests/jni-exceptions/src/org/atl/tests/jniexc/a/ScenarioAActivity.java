/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.a;

import android.app.Activity;
import android.os.Bundle;

import org.atl.tests.jniexc.ProbeJson;

/**
 * JNI-EXC-001 scenario A (primary negative scenario).
 *
 * onCreate() throws before super.onCreate(), so the throwable escapes into the
 * native-owned activity_start() boundary (src/api-impl-jni/app/android_app_Activity.c:185).
 *
 * The deterministic JSON block is printed first (it needs no Context), then the
 * throwable is raised. SENTINEL-JNI-EXC-001-A is reachable ONLY if ATL continues
 * past the failed onCreate and drives the later lifecycle callbacks.
 *
 * Expected baseline (frozen base SHA): CheckJNI abort ("JNI GetObjectClass called
 * with pending exception ...") and/or the sentinel line. Expected candidate
 * (fixed): the original IllegalStateException/cause is the terminal record, no
 * pending-exception abort, sentinel absent.
 */
public class ScenarioAActivity extends Activity {

    private void sentinel(String where) {
        System.out.println(ProbeJson.SENTINEL_A + " " + where);
        System.out.flush();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ProbeJson.emit("A", ProbeJson.instanceId("A"));
        // Negative scenario: throw before super.onCreate().
        throw new IllegalStateException("JNI-EXC-001-A",
                new UnsupportedOperationException("origin-A"));
    }

    // Reached only if the native boundary wrongly continues after the failure.
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        sentinel("onPostCreate");
    }

    @Override
    protected void onStart() {
        sentinel("onStart");
    }

    @Override
    protected void onResume() {
        sentinel("onResume");
    }

    @Override
    protected void onPostResume() {
        sentinel("onPostResume");
    }
}
