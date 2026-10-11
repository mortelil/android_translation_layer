/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.c;

import android.app.Activity;
import android.os.Bundle;

import org.atl.tests.jniexc.ProbeJson;

/**
 * JNI-EXC-001 scenario C (positive control).
 *
 * The exact same throwable as scenario A is raised, but it is caught inside the
 * Java callback. The native activity_start() boundary must therefore never see a
 * pending exception and must complete normally. Completion is signalled by the
 * marker JNIEXC001_POS_DONE; the same JSON block is written to files/results.json.
 *
 * This APK is a normal, installable Android APK (no ATL-only behaviour).
 */
public class ScenarioCActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = ProbeJson.instanceId("C");
        String json = ProbeJson.config("C", id);

        System.out.println(ProbeJson.JSON_BEGIN);
        System.out.println(json);
        System.out.println(ProbeJson.JSON_END);
        System.out.flush();

        // Same throwable; caught inside the callback -> no pending exception at
        // the native boundary.
        try {
            throw new IllegalStateException("JNI-EXC-001-A",
                    new UnsupportedOperationException("origin-A"));
        } catch (IllegalStateException caught) {
            // deliberate: the callback handles its own failure
        }

        ProbeJson.writeResults(getFilesDir(), json);
        System.out.println(ProbeJson.POS_DONE);
        System.out.flush();
        // Deliberately do NOT call finish(): the runner must poll the JNIEXC001_POS_DONE
        // marker + results.json, never process exit (ATL GUI apps do not self-exit).
    }
}
