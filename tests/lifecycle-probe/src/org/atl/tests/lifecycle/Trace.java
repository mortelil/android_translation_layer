/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable ordered trace. Every event line is appended to
 * {@code getFilesDir()/trace.log} and flushed immediately (so the trace
 * survives a normal window close), mirrored to stdout, and kept in memory for
 * the aggregate results.json written at the end of the non-destructive
 * scenarios.
 *
 * Line formats:
 *   LIFE_TRACE_BEGIN ...                       (header, run metadata)
 *   LIFE_SCEN_BEGIN <scenario-id>
 *   LIFE_SCEN_END   <scenario-id>
 *   LIFE_EVT {json}                            (one JSON object per event)
 *   LIFE_EXCEPTION <text>
 *   LIFE_JSON_BEGIN / <json> / LIFE_JSON_END   (aggregate, printed by ProbeApp)
 *   LIFE_ALL_DONE
 */
final class Trace {
    private final FileOutputStream raw;
    private final Writer writer;
    private final List<String> events = new ArrayList<String>();
    private final List<String> endedScenarios = new ArrayList<String>();
    private final List<String> exceptions = new ArrayList<String>();
    private String scenario = "-";
    private int seq = 0;

    Trace(File dir, String runId, String taskId) throws Exception {
        File f = new File(dir, "trace.log");
        raw = new FileOutputStream(f, false);
        writer = new OutputStreamWriter(raw, "UTF-8");
        line("LIFE_TRACE_BEGIN task_id=" + taskId
                + " run_id=" + runId
                + " package=" + ProbeApp.PKG
                + " probe=" + ProbeApp.PROBE_ID);
    }

    synchronized String scenario() {
        return scenario;
    }

    synchronized void line(String s) {
        try {
            writer.write(s);
            writer.write("\n");
            writer.flush();
            raw.flush();
        } catch (Exception e) {
            // A trace write failure must not abort the lifecycle drive; the
            // stdout mirror below is the only remaining signal.
        }
        System.out.println(s);
    }

    synchronized void scenBegin(String id) {
        scenario = id;
        line("LIFE_SCEN_BEGIN " + id);
    }

    synchronized void scenEnd(String id) {
        endedScenarios.add(id);
        line("LIFE_SCEN_END " + id);
    }

    /** Event line attributed to the currently open scenario. */
    synchronized void event(String actor, String eventName, String activity,
                            String bundle, String phase) {
        emit(scenario, actor, eventName, activity, bundle, phase);
    }

    /** Event line with an explicit scenario (used before S1 opens). */
    synchronized void nonScenarioEvent(String scen, String actor, String eventName,
                                       String activity, String bundle, String phase) {
        emit(scen, actor, eventName, activity, bundle, phase);
    }

    private synchronized void emit(String scen, String actor, String eventName,
                                   String activity, String bundle, String phase) {
        int n = ++seq;
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        String json = "{\"k\":\"evt\",\"seq\":" + n
                + ",\"scenario\":\"" + esc(scen) + "\""
                + ",\"actor\":\"" + esc(actor) + "\""
                + ",\"event\":\"" + esc(eventName) + "\""
                + ",\"activity\":\"" + esc(activity) + "\""
                + ",\"bundle\":\"" + esc(bundle) + "\""
                + ",\"phase\":\"" + esc(phase) + "\""
                + ",\"owner\":\"ProbeApp\""
                + ",\"thread\":\"" + esc(Thread.currentThread().getName()) + "\""
                + ",\"threadRole\":\"" + (main ? "main" : "other") + "\""
                + ",\"main\":" + main
                + "}";
        events.add(json);
        line("LIFE_EVT " + json);
    }

    synchronized List<String> events() {
        return new ArrayList<String>(events);
    }

    synchronized List<String> endedScenarios() {
        return new ArrayList<String>(endedScenarios);
    }

    synchronized void recordException(String text) {
        exceptions.add(text);
        line("LIFE_EXCEPTION " + text);
    }

    synchronized List<String> exceptions() {
        return new ArrayList<String>(exceptions);
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }
}
