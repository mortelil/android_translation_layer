/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.content.Context;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ordered JSON event log + result transport for the LOCAL-SERVICES probe v3
 * (cases SVC-002 / SVC-003 / SVC-004 preserved, plus SVC-005 / SVC-006 /
 * SVC-007 / SVC-008 / SVC-009 appended).
 *
 * Every event is one JSON row carrying the v2 fields fixed by the case specs:
 *   seq, scenario, component, instance, event, startId, flags,
 *   intentExtra, cmd, returnValue, thread, ret
 * (plus non-normative retType/main/exception extras) and the v3 binding fields:
 *   client (connection id, e.g. "c1"/"c2") and
 *   binder (String identity hash of the involved IBinder, else null).
 *
 * Transport (same shape as the lifecycle + SVC-001 probes):
 *  - one durable line per event to getFilesDir()/events.log and stdout
 *    ({@code SVC_EVT {json}}), flushed immediately;
 *  - the aggregate document written to getFilesDir()/results.json after every
 *    event (so a crash still leaves partial evidence) and once more on finalize;
 *  - the aggregate also printed to stdout between SVC_JSON_BEGIN / SVC_JSON_END
 *    (completion marker = the single SVC_JSON_END line, printed once for the
 *    whole run after all three scenarios).
 *
 * It is a singleton so Service callbacks (same process) attribute themselves to
 * the same run. Service *instances* are indexed by OBJECT IDENTITY (never by
 * address) and the index is scoped PER SCENARIO, so each scenario's fresh
 * instance is instance=1 exactly as the specs' planned traces require.
 */
final class EventLog {
    static final String TASK_ID = "LOCAL-SERVICES-PROBE-V4";
    static final String PROBE_ID = "SVC-PROBE-V4";
    static final String MARKER_BEGIN = "SVC_JSON_BEGIN";
    static final String MARKER_END = "SVC_JSON_END";
    static final String PKG = "org.atl.tests.svc";

    static final String SVC_002 = "SVC-002";
    static final String SVC_003 = "SVC-003";
    static final String SVC_004 = "SVC-004";
    static final String SVC_005 = "SVC-005";
    static final String SVC_006 = "SVC-006";
    static final String SVC_007 = "SVC-007";
    static final String SVC_008 = "SVC-008";
    static final String SVC_009 = "SVC-009";
    static final String SVC_010 = "SVC-010";
    static final String SVC_011 = "SVC-011";
    static final String SVC_013 = "SVC-013";
    static final String SVC_016 = "SVC-016";

    /** Component name fields used by the v2 scenarios (labelled "component A" per spec). */
    static final String COMP_002 = PKG + "/.SvcStartIds";
    static final String COMP_003 = PKG + "/.SvcReturnValues";
    static final String COMP_004 = PKG + "/.SvcSelfStop";

    /* v3 binding components. */
    /** B — onBind returns a private binder; default onUnbind (false). */
    static final String COMP_B = PKG + "/.SvcBinder";
    /** Bn — onBind returns null. */
    static final String COMP_BN = PKG + "/.SvcNullBind";
    /** Bf — onUnbind returns false. */
    static final String COMP_BF = PKG + "/.SvcUnbindFalse";
    /** Bt — onUnbind returns true, implements onRebind. */
    static final String COMP_BT = PKG + "/.SvcUnbindTrue";

    /* v4 components. */
    /** B — SVC-010/011: logs onStartCommand too; default onUnbind (false). */
    static final String COMP_V4B = PKG + "/.SvcV4B";
    /** A — SVC-016: stopSelfResult/stopSelf(current start id). */
    static final String COMP_STOP_SELF = PKG + "/.SvcStopSelfV4";
    /** Ir — SVC-013a: re-enters startService from onStartCommand. */
    static final String COMP_RE_START = PKG + "/.SvcReenterStart";
    /** Ir2 — SVC-013b: bound service; client unbinds from onServiceConnected. */
    static final String COMP_RE_BIND = PKG + "/.SvcReenterBind";
    /** Ir3 — SVC-013c: re-enters bindService from onUnbind. */
    static final String COMP_RE_UNBIND = PKG + "/.SvcReenterUnbind";

    /**
     * Scenario the currently executing service callbacks belong to. v3 reuses
     * one component class across SVC-005/006/007, so a service cannot hard-code
     * its scenario; the driver stamps it here before each step (main thread).
     */
    private static String currentScenario = SVC_002;

    static synchronized void setCurrentScenario(String sid) {
        currentScenario = sid;
    }

    static synchronized String currentScenario() {
        return currentScenario;
    }

    private static EventLog INSTANCE;

    private final Context appContext;
    private final String runId;
    private final File filesDir;
    private final Writer eventWriter;
    private final List<String> scenarios = new ArrayList<String>();
    private final Map<String, List<String>> events =
            new LinkedHashMap<String, List<String>>();
    private final Map<String, IdentityHashMap<Object, Integer>> instanceIndex =
            new LinkedHashMap<String, IdentityHashMap<Object, Integer>>();
    private final Map<String, Integer> nextInstance = new LinkedHashMap<String, Integer>();
    private final List<String> exceptions = new ArrayList<String>();
    private int seq = 0;
    private boolean finalized = false;

    private EventLog(Context ctx, String[] scenarioIds) {
        this.appContext = ctx.getApplicationContext();
        this.filesDir = this.appContext.getFilesDir();
        this.runId = UUID.randomUUID().toString();
        for (int i = 0; i < scenarioIds.length; i++) {
            scenarios.add(scenarioIds[i]);
            events.put(scenarioIds[i], new ArrayList<String>());
            instanceIndex.put(scenarioIds[i], new IdentityHashMap<Object, Integer>());
            nextInstance.put(scenarioIds[i], Integer.valueOf(1));
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(
                    new FileOutputStream(new File(filesDir, "events.log"), false), "UTF-8");
        } catch (Exception e) {
            // stdout is the fallback transport; never abort the scenario.
        }
        this.eventWriter = w;
    }

    static synchronized EventLog init(Context ctx, String[] scenarioIds) {
        if (INSTANCE == null) {
            INSTANCE = new EventLog(ctx, scenarioIds);
        }
        return INSTANCE;
    }

    static synchronized EventLog get() {
        return INSTANCE;
    }

    String runId() {
        return runId;
    }

    /** Identity keyed logical instance index within one scenario; first is 1. */
    private synchronized int instanceOf(String scenario, Object serviceIdentity) {
        IdentityHashMap<Object, Integer> map = instanceIndex.get(scenario);
        if (map == null) {
            map = new IdentityHashMap<Object, Integer>();
            instanceIndex.put(scenario, map);
        }
        Integer idx = map.get(serviceIdentity);
        if (idx == null) {
            Integer next = nextInstance.get(scenario);
            int n = (next == null) ? 1 : next.intValue();
            idx = Integer.valueOf(n);
            nextInstance.put(scenario, Integer.valueOf(n + 1));
            map.put(serviceIdentity, idx);
        }
        return idx.intValue();
    }

    /** Client-side call event (v2 shape, no binding identity). */
    synchronized void clientEvent(String scenario, String event, String component,
                                  String intentExtra, String cmd, String ret,
                                  String retType, String exceptionClass) {
        append(scenario, null, event, component, -1, -1, intentExtra, cmd, null,
                ret, retType, exceptionClass, null, null);
    }

    /**
     * Client-side call event with a connection id and (optionally) a binder
     * identity hash and explicit {@code flags} (used by the v3 binding steps).
     */
    synchronized void clientEventEx(String scenario, String event, String component,
                                    String intentExtra, String cmd, String client,
                                    String binder, int flags, String ret,
                                    String retType, String exceptionClass) {
        append(scenario, null, event, component, -1, flags, intentExtra, cmd, null,
                ret, retType, exceptionClass, client, binder);
    }

    /** Service callback event (v2 shape, no binding identity). */
    synchronized void serviceEvent(Object serviceIdentity, String scenario, String event,
                                   String component, int startId, int flags,
                                   String intentExtra, String cmd, Integer returnValue,
                                   String ret) {
        append(scenario, serviceIdentity, event, component, startId, flags,
                intentExtra, cmd, returnValue, ret, null, null, null, null);
    }

    /**
     * Service callback event with the identity hash of the binder it returns
     * (onBind) or created (onCreate), matching the client-side onServiceConnected
     * hash so binder reuse is comparable within the process.
     */
    synchronized void serviceEventB(Object serviceIdentity, String scenario, String event,
                                    String component, int startId, int flags,
                                    String intentExtra, String cmd, Integer returnValue,
                                    String ret, String binder) {
        append(scenario, serviceIdentity, event, component, startId, flags,
                intentExtra, cmd, returnValue, ret, null, null, null, binder);
    }

    /** Barrier event: proves the main-Looper no-op runnable drained the queue. */
    synchronized void barrierEvent(String scenario, String component, String client) {
        append(scenario, null, "barrier", component, -1, -1, "none", "", null,
                null, null, null, client, null);
    }

    /**
     * A Service instance has been destroyed: drop its identity mapping so the
     * NEXT fresh instance of the same scenario is indexed 1 again (the specs'
     * planned traces re-number after onDestroy, e.g. SVC-008a step 3).
     */
    synchronized void serviceDestroyed(String scenario, Object serviceIdentity) {
        IdentityHashMap<Object, Integer> map = instanceIndex.get(scenario);
        if (map == null) {
            return;
        }
        map.remove(serviceIdentity);
        if (map.isEmpty()) {
            nextInstance.put(scenario, Integer.valueOf(1));
        }
    }

    synchronized void noteException(String text) {
        exceptions.add(text);
        line("SVC_EXCEPTION " + text);
    }

    private synchronized void append(String scenario, Object serviceIdentity, String event,
                                     String component, int startId, int flags,
                                     String intentExtra, String cmd, Integer returnValue,
                                     String ret, String retType, String exceptionClass,
                                     String client, String binder) {
        if (finalized) {
            return;
        }
        int n = ++seq;
        Integer instance = serviceIdentity == null
                ? null : Integer.valueOf(instanceOf(scenario, serviceIdentity));
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        String thread = Thread.currentThread().getName();
        StringBuilder b = new StringBuilder(288);
        b.append("{\"seq\":").append(n);
        b.append(",\"scenario\":\"").append(esc(scenario)).append('"');
        b.append(",\"component\":\"").append(esc(component)).append('"');
        b.append(",\"instance\":").append(instance == null ? "null" : instance);
        b.append(",\"event\":\"").append(esc(event)).append('"');
        b.append(",\"startId\":").append(startId < 0 ? "null" : Integer.toString(startId));
        b.append(",\"flags\":").append(flags < 0 ? "null" : Integer.toString(flags));
        b.append(",\"intentExtra\":\"").append(esc(intentExtra)).append('"');
        b.append(",\"cmd\":\"").append(esc(cmd)).append('"');
        b.append(",\"returnValue\":")
                .append(returnValue == null ? "null" : returnValue.toString());
        b.append(",\"thread\":\"").append(esc(thread)).append('"');
        b.append(",\"ret\":").append(ret == null ? "null" : "\"" + esc(ret) + "\"");
        b.append(",\"client\":").append(client == null ? "null" : "\"" + esc(client) + "\"");
        b.append(",\"binder\":").append(binder == null ? "null" : "\"" + esc(binder) + "\"");
        if (retType != null) {
            b.append(",\"retType\":\"").append(esc(retType)).append('"');
        }
        b.append(",\"main\":").append(main);
        if (exceptionClass != null) {
            b.append(",\"exception\":\"").append(esc(exceptionClass)).append('"');
        }
        b.append('}');
        String json = b.toString();
        List<String> bucket = events.get(scenario);
        if (bucket == null) {
            bucket = new ArrayList<String>();
            events.put(scenario, bucket);
        }
        bucket.add(json);
        line("SVC_EVT " + json);
        flushResults();
    }

    /** Write the aggregate results.json snapshot (no stdout markers). */
    synchronized void flushResults() {
        writeResults(buildResultsJson(), false);
    }

    /**
     * Final aggregate: write results.json and print the single marker block to
     * stdout. Completion signal for the runner is the SVC_JSON_END line.
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
        line("SVC_ALL_DONE");
    }

    private static String servicesFor(String sid) {
        if (SVC_002.equals(sid)) {
            return PKG + ".SvcStartIds";
        }
        if (SVC_003.equals(sid)) {
            return PKG + ".SvcReturnValues";
        }
        if (SVC_004.equals(sid)) {
            return PKG + ".SvcSelfStop";
        }
        if (SVC_005.equals(sid) || SVC_006.equals(sid) || SVC_007.equals(sid)) {
            return PKG + ".SvcBinder";
        }
        if (SVC_008.equals(sid)) {
            return PKG + ".SvcUnbindFalse," + PKG + ".SvcUnbindTrue";
        }
        if (SVC_009.equals(sid)) {
            return PKG + ".SvcNullBind";
        }
        if (SVC_010.equals(sid) || SVC_011.equals(sid)) {
            return PKG + ".SvcV4B";
        }
        if (SVC_013.equals(sid)) {
            return PKG + ".SvcReenterStart," + PKG + ".SvcReenterBind,"
                    + PKG + ".SvcReenterUnbind";
        }
        if (SVC_016.equals(sid)) {
            return PKG + ".SvcStopSelfV4";
        }
        return "";
    }

    private String buildResultsJson() {
        StringBuilder b = new StringBuilder(8192);
        b.append("{\"task_id\":\"").append(TASK_ID).append('"');
        b.append(",\"probe\":\"").append(PROBE_ID).append('"');
        b.append(",\"run_id\":\"").append(esc(runId)).append('"');
        b.append(",\"config\":{");
        b.append("\"package\":\"").append(PKG).append('"');
        b.append(",\"main_activity\":\"").append(PKG).append(".ControlActivity\"");
        b.append(",\"scenarios\":[");
        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(esc(scenarios.get(i))).append('"');
        }
        b.append("],\"services\":{");
        for (int i = 0; i < scenarios.size(); i++) {
            String sid = scenarios.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(esc(sid)).append("\":\"")
                    .append(esc(servicesFor(sid))).append('"');
        }
        b.append('}');
        b.append(",\"launch\":\"launcher\"");
        b.append(",\"settle_ms\":").append(ControlActivity.SETTLE_MS);
        b.append(",\"barrier_ms\":").append(ControlActivity.BARRIER_MS);
        b.append(",\"watchdog_ms\":").append(ControlActivity.WATCHDOG_MS);
        b.append('}');
        int total = 0;
        for (int i = 0; i < scenarios.size(); i++) {
            List<String> bucket = events.get(scenarios.get(i));
            total += (bucket == null) ? 0 : bucket.size();
        }
        b.append(",\"observations\":{");
        b.append("\"event_count\":").append(total);
        b.append(",\"scenarios_completed\":[");
        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(esc(scenarios.get(i))).append('"');
        }
        b.append(']');
        b.append(",\"scenarios_observed\":[");
        boolean first = true;
        for (int i = 0; i < scenarios.size(); i++) {
            String sid = scenarios.get(i);
            List<String> bucket = events.get(sid);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append('"').append(esc(sid)).append('"');
        }
        b.append(']');
        b.append('}');
        b.append(",\"scenarios\":[");
        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            String sid = scenarios.get(i);
            List<String> bucket = events.get(sid);
            b.append("{\"scenario\":\"").append(esc(sid)).append('"');
            b.append(",\"events\":[").append(bucket == null ? "" : join(bucket)).append("]}");
        }
        b.append(']');
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
            System.out.println("SVC_RESULTS_FILE " + new File(filesDir, "results.json"));
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
