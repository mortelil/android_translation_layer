/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.h;

import android.app.Application;

import org.atl.tests.jniexc.ProbeJson;

/**
 * JNI-EXC-001 scenario H - Application construction failure.
 *
 * The Application object itself fails to construct: this constructor throws
 * before ATL has built any Activity. ATL's GTK "open" startup sequence
 * instantiates the manifest application class, so the throwable escapes into
 * the native-owned "Application construction" boundary (main.c open()).
 *
 * Pre-fix behaviour: ATL continued with the exception pending and the next
 * FindClass aborted the process under -Xcheck:jni ("JNI FindClass called with
 * pending exception ... Runtime aborting ... Fatal signal 6").
 * Required behaviour: this original IllegalStateException is the terminal
 * record; startup ends controlled; no pending-exception abort.
 *
 * The deterministic JSON block is written to BOTH stdout and stderr because
 * ATL forwards Java output on either stream depending on the console setup;
 * the analyzer scans both. SENTINEL-JNI-EXC-001-H (see ScenarioHActivity) is
 * reachable only if ATL wrongly continues past the failed construction.
 */
public class ScenarioHApplication extends Application {

    public ScenarioHApplication() {
        super();
        evidence();
        throw new IllegalStateException("JNI-EXC-001-H application-constructor");
    }

    private static void evidence() {
        String json = "{\"task_id\":\"" + ProbeJson.TASK_ID + "\""
                + ",\"case\":\"H\""
                + ",\"instance_id\":\"" + ProbeJson.instanceId("H") + "\""
                + ",\"config\":{"
                + "\"scenario\":\"application-construction\""
                + ",\"throwable\":\"java.lang.IllegalStateException\""
                + ",\"message\":\"JNI-EXC-001-H application-constructor\""
                + "}}";
        emit(System.out, json);
        emit(System.err, json);
    }

    private static void emit(java.io.PrintStream ps, String json) {
        ps.println(ProbeJson.JSON_BEGIN);
        ps.println(json);
        ps.println(ProbeJson.JSON_END);
        ps.flush();
    }
}