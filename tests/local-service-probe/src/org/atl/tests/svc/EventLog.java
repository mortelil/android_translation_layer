/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Ordered JSON event log + result transport for the LOCAL-SERVICES probe.
 *
 * Every event is one JSON row with the fields required by the case spec:
 *   seq, scenario, component, instance, event, startId, flags, intentExtra,
 *   thread, ret   (plus retType/main/exception as non-normative extras).
 *
 * Transport (same as the lifecycle probe):
 *  - one durable line per event to getFilesDir()/events.log and stdout
 *    ({@code SVC001_EVT {json}}), flushed immediately;
 *  - the aggregate document written to getFilesDir()/results.json (a fresh
 *    snapshot after every event, so a crash still leaves partial evidence);
 *  - the aggregate also printed to stdout between SVC001_JSON_BEGIN /
 *    SVC001_JSON_END (completion marker = SVC001_JSON_END).
 *
 * It is a singleton so Service callbacks (which run in the same process) can
 * attribute themselves to the same run. Instances are indexed by OBJECT
 * IDENTITY within this process (never by address), so the same Service object
 * keeps one logical instance index across all of its callbacks.
 */
final class EventLog {
    static final String TASK_ID = "LOCAL-SERVICES-PROBE-001";
    static final String PROBE_ID = "SVC-001";
    static final String MARKER_BEGIN = "SVC001_JSON_BEGIN";
    static final String MARKER_END = "SVC001_JSON_END";
    static final String PKG = "org.atl.tests.svc";

    private static EventLog INSTANCE;

    private final Context appContext;
    private final String runId;
    private final File filesDir;
    private final Writer eventWriter;
    private final List<String> events = new ArrayList<String>();
    private final List<String> exceptions = new ArrayList<String>();
    private final IdentityHashMap<Object, Integer> instanceIndex =
            new IdentityHashMap<Object, Integer>();
    private final String scenario;
    private int seq = 0;
    private int nextInstance = 1;
    private boolean finalized = false;

    private EventLog(Context ctx, String scenario) {
        this.appContext = ctx.getApplicationContext();
        this.filesDir = this.appContext.getFilesDir();
        this.scenario = scenario;
        this.runId = UUID.randomUUID().toString();
        Writer w = null;
        try {
            w = new OutputStreamWriter(
                    new FileOutputStream(new File(filesDir, "events.log"), false), "UTF-8");
        } catch (Exception e) {
            // stdout is the fallback transport; never abort the scenario.
        }
        this.eventWriter = w;
    }

    static synchronized EventLog init(Context ctx, String scenario) {
        if (INSTANCE == null) {
            INSTANCE = new EventLog(ctx, scenario);
        }
        return INSTANCE;
    }

    static synchronized EventLog get() {
        return INSTANCE;
    }

    String runId() {
        return runId;
    }

    /** Identity keyed logical instance index; first instance observed is 1. */
    private synchronized int instanceOf(Object serviceIdentity) {
        Integer idx = instanceIndex.get(serviceIdentity);
        if (idx == null) {
            idx = Integer.valueOf(nextInstance++);
            instanceIndex.put(serviceIdentity, idx);
        }
        return idx.intValue();
    }

    /** Client-side call event (no live Service object). */
    synchronized void clientEvent(String event, String component, String ret,
                                  String retType, String exceptionClass) {
        append(System.currentTimeMillis(), null, event, component, -1, -1, "none",
                ret, retType, exceptionClass);
    }

    /** Service callback event: instance/thread attributed to the Service object. */
    synchronized void serviceEvent(Object serviceIdentity, String event, String component,
                                   int startId, int flags, String intentExtra) {
        append(System.currentTimeMillis(), serviceIdentity, event, component,
                startId, flags, intentExtra, null, null, null);
    }

    synchronized void noteException(String text) {
        exceptions.add(text);
        line("SVC001_EXCEPTION " + text);
    }

    private synchronized void append(long ts, Object serviceIdentity, String event,
                                     String component, int startId, int flags,
                                     String intentExtra, String ret, String retType,
                                     String exceptionClass) {
        if (finalized) {
            return;
        }
        int n = ++seq;
        Integer instance = serviceIdentity == null
                ? null : Integer.valueOf(instanceOf(serviceIdentity));
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        String thread = Thread.currentThread().getName();
        StringBuilder b = new StringBuilder(256);
        b.append("{\"seq\":").append(n);
        b.append(",\"scenario\":\"").append(esc(scenario)).append('"');
        b.append(",\"component\":\"").append(esc(component)).append('"');
        b.append(",\"instance\":").append(instance == null ? "null" : instance);
        b.append(",\"event\":\"").append(esc(event)).append('"');
        b.append(",\"startId\":").append(startId < 0 ? "null" : Integer.toString(startId));
        b.append(",\"flags\":").append(flags < 0 ? "null" : Integer.toString(flags));
        b.append(",\"intentExtra\":\"").append(esc(intentExtra)).append('"');
        b.append(",\"thread\":\"").append(esc(thread)).append('"');
        b.append(",\"ret\":").append(ret == null ? "null" : "\"" + esc(ret) + "\"");
        if (retType != null) {
            b.append(",\"retType\":\"").append(esc(retType)).append('"');
        }
        b.append(",\"main\":").append(main);
        if (exceptionClass != null) {
            b.append(",\"exception\":\"").append(esc(exceptionClass)).append('"');
        }
        b.append('}');
        String json = b.toString();
        events.add(json);
        line("SVC001_EVT " + json);
        flushResults();
    }

    /** Write the aggregate results.json snapshot (no stdout markers). */
    synchronized void flushResults() {
        writeResults(buildResultsJson(), false);
    }

    /**
     * Final aggregate: write results.json and print the marker block to stdout.
     * Completion signal for the runner is the SVC001_JSON_END line.
     */
    synchronized void finalizeRun() {
        if (finalized) {
            return;
        }
        finalized = true;
        String json = buildResultsJson();
        writeResults(json, true);
        line(MARKER_BEGIN);
        line(json);
        line(MARKER_END);
        line("SVC001_ALL_DONE");
    }

    private String buildResultsJson() {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"task_id\":\"").append(TASK_ID).append('"');
        b.append(",\"probe\":\"").append(PROBE_ID).append('"');
        b.append(",\"case\":\"").append(PROBE_ID).append('"');
        b.append(",\"run_id\":\"").append(esc(runId)).append('"');
        b.append(",\"config\":{");
        b.append("\"package\":\"").append(PKG).append('"');
        b.append(",\"main_activity\":\"").append(PKG).append(".ControlActivity\"");
        b.append(",\"service\":\"").append(PKG).append(".SvcA\"");
        b.append(",\"declared_extra_component\":\"").append(PKG).append(".SvcB\"");
        b.append(",\"unknown_component\":\"").append(PKG).append(".SvcUnknown\"");
        b.append(",\"scenario\":\"").append(PROBE_ID).append('"');
        b.append(",\"launch\":\"launcher\"");
        b.append(",\"intent_extras\":false");
        b.append(",\"settle_ms\":").append(ControlActivity.SETTLE_MS);
        b.append('}');
        b.append(",\"observations\":{");
        b.append("\"event_count\":").append(events.size());
        b.append(",\"scenarios_completed\":[\"").append(PROBE_ID).append("\"]");
        b.append('}');
        b.append(",\"events\":[").append(join(events)).append(']');
        b.append(",\"exceptions\":[").append(joinQuoted(exceptions)).append(']');
        b.append('}');
        return b.toString();
    }

    private void writeResults(String json, boolean alsoStdout) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(
                    new FileOutputStream(new File(filesDir, "results.json"), false), "UTF-8");
            w.write(json);
            w.flush();
        } catch (Exception e) {
            noteException("results.json write failed: " + e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
        if (alsoStdout) {
            System.out.println("SVC001_RESULTS_FILE " + new File(filesDir, "results.json"));
        }
    }

    private void line(String s) {
        if (eventWriter != null) {
            try {
                eventWriter.write(s);
                eventWriter.write("\n");
                eventWriter.flush();
            } catch (Exception e) {
                // stdout mirror below remains.
            }
        }
        System.out.println(s);
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(items.get(i));
        }
        return b.toString();
    }

    private static String joinQuoted(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(esc(items.get(i))).append('"');
        }
        return b.toString();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
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
