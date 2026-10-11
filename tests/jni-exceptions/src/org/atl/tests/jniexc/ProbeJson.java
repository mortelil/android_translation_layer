/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Shared, deterministic output helpers for the JNI-EXC-001 probe family.
 *
 * Every probe emits one sentinel-wrapped JSON object on stdout before it does
 * anything observable (JNIEXC001_JSON_BEGIN ... JNIEXC001_JSON_END). Scenario A
 * and B then throw; scenario C catches the throwable, writes the same JSON to
 * files/results.json and prints the positive completion marker JNIEXC001_POS_DONE.
 *
 * The completion signal used by the runner is one of the markers below (never
 * process exit; see agent-work/tooling.md section 8).
 */
public final class ProbeJson {
    public static final String TASK_ID = "JNI-EXC-001";
    public static final String JSON_BEGIN = "JNIEXC001_JSON_BEGIN";
    public static final String JSON_END = "JNIEXC001_JSON_END";
    public static final String POS_DONE = "JNIEXC001_POS_DONE";
    /** Reachable only if ATL wrongly continues past the failed onCreate (case A). */
    public static final String SENTINEL_A = "SENTINEL-JNI-EXC-001-A";
    /** Variant sentinel for the formatter-throws scenario (case B). */
    public static final String SENTINEL_B = "SENTINEL-JNI-EXC-001-B";
    public static final String RESULTS_FILE = "results.json";

    private ProbeJson() {}

    /** Per-process instance id; the harness supplies the per-run id (see run-case.sh). */
    public static String instanceId(String probeCase) {
        return "jniexc001-" + probeCase + "-apk-"
                + Long.toHexString(System.currentTimeMillis()) + "-"
                + Long.toHexString(System.nanoTime());
    }

    /** The deterministic JSON object emitted by every scenario. */
    public static String config(String probeCase, String instanceId) {
        return "{\"task_id\":\"" + TASK_ID + "\""
                + ",\"case\":\"" + probeCase + "\""
                + ",\"instance_id\":\"" + instanceId + "\""
                + ",\"config\":{"
                + "\"throwable\":\"java.lang.IllegalStateException\""
                + ",\"message\":\"JNI-EXC-001-A\""
                + ",\"cause\":\"java.lang.UnsupportedOperationException\""
                + ",\"causeMessage\":\"origin-A\""
                + "}}";
    }

    /** Print the sentinel-wrapped JSON block and flush immediately. */
    public static void emit(String probeCase, String instanceId) {
        System.out.println(JSON_BEGIN);
        System.out.println(config(probeCase, instanceId));
        System.out.println(JSON_END);
        System.out.flush();
    }

    /** Persist the JSON next to the (optional) results.json the runner collects. */
    public static void writeResults(File filesDir, String json) {
        if (filesDir == null) {
            return;
        }
        try {
            Writer w = new OutputStreamWriter(
                    new FileOutputStream(new File(filesDir, RESULTS_FILE)), "UTF-8");
            try {
                w.write(json);
                w.write("\n");
                w.flush();
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // results.json is a convenience artifact; stdout carries the same JSON.
        }
    }
}
