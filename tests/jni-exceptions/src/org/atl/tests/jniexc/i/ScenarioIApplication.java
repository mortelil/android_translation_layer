/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.i;

import android.app.Application;

import org.atl.tests.jniexc.ProbeJson;

/**
 * JNI-EXC-001 scenario I - missing native library during Application startup.
 *
 * Application.onCreate() asks for a native library that is not present in the
 * APK, which raises UnsatisfiedLinkError inside the Application subclass
 * (the morphe/youtube-x86 shape). The throwable escapes into the native-owned
 * "Application.onCreate" boundary in main.c open().
 *
 * Pre-fix behaviour: ATL continued with the exception pending and the next
 * NewStringUTF aborted the process under -Xcheck:jni
 * ("JNI NewStringUTF called with pending exception ... Fatal signal 6").
 * Required behaviour: the original UnsatisfiedLinkError (naming the missing
 * library) is the terminal record; no pending-exception abort.
 *
 * SENTINEL-JNI-EXC-001-I (see ScenarioIActivity) is reachable only if ATL
 * wrongly continues past the failed onCreate.
 */
public class ScenarioIApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        evidence();
        System.loadLibrary("jniexc001-absent-library");
    }

    private static void evidence() {
        String json = "{\"task_id\":\"" + ProbeJson.TASK_ID + "\""
                + ",\"case\":\"I\""
                + ",\"instance_id\":\"" + ProbeJson.instanceId("I") + "\""
                + ",\"config\":{"
                + "\"scenario\":\"missing-native-library\""
                + ",\"library\":\"jniexc001-absent-library\""
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