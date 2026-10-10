/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * LOCAL-SERVICES probe v2 driver: runs SVC-002, SVC-003 and SVC-004 in order on
 * the main thread and emits ONE aggregate result (getFilesDir()/results.json +
 * the single SVC_JSON_BEGIN/SVC_JSON_END stdout block) after all three finish.
 *
 * Key properties (per the assignment):
 *  - The Activity stays alive for the whole run (never finishes itself).
 *  - Every step is posted with {@link Handler#postDelayed}; the probe NEVER
 *    blocks waiting for a Service callback. If a platform delivers no callbacks
 *    the trace simply lacks them and the run still reaches its end marker.
 *  - A bounded watchdog ({@link #WATCHDOG_MS}) forces the aggregate so the
 *    runner always sees SVC_JSON_END even if some step hangs.
 *
 * Scenario inputs (all fixed):
 *  SVC-002 — startService(A, k=1), (k=2), (k=1), stopService(A)
 *  SVC-003 — startService(A, want=2), (want=3), (want=none), stopService(A)
 *  SVC-004 — startService(A, cmd=none), (cmd=stopOld, oldId=1), (cmd=none),
 *            (cmd=stopSelfNow), stopService(A)
 * A = the scenario's service component (see EventLog.COMP_00x).
 */
public class ControlActivity extends Activity {
    /** Delay before the very first client step, to let the window settle. */
    static final long INITIAL_MS = 500L;
    /** Delay between client steps, to let async Service callbacks land. */
    static final long SETTLE_MS = 700L;
    /** Extra pause between scenarios (allows onDestroy / next fresh instance). */
    static final long GAP_MS = 1400L;
    /** Delay after the last client step before the aggregate is finalized. */
    static final long FINAL_MS = 1500L;
    /** Hard bound: force the end marker if the step chain has not reached it. */
    static final long WATCHDOG_MS = 30000L;

    private static final Runnable NOP = new Runnable() {
        @Override
        public void run() {
            // deliberate gap; nothing to do
        }
    };

    private EventLog log;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Step> steps = new ArrayList<Step>();
    private int cursor = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("ATL LOCAL-SERVICES probe v2 (SVC-002/003/004) running");
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(tv);

        log = EventLog.init(this, new String[]{
                EventLog.SVC_002, EventLog.SVC_003, EventLog.SVC_004});
        buildSteps();
        handler.postDelayed(watchdog, WATCHDOG_MS);
        pump();
    }

    /** Bounded watchdog: guarantees the single end marker is always emitted. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            log.noteException("watchdog fired after " + (WATCHDOG_MS / 1000)
                    + "s: forcing aggregate");
            log.finalizeRun();
        }
    };

    private void buildSteps() {
        addStart(EventLog.SVC_002, EventLog.COMP_002, intentA(EventLog.COMP_002, "k", 1),
                "1", "", INITIAL_MS);
        addStart(EventLog.SVC_002, EventLog.COMP_002, intentA(EventLog.COMP_002, "k", 2),
                "2", "", SETTLE_MS);
        addStart(EventLog.SVC_002, EventLog.COMP_002, intentA(EventLog.COMP_002, "k", 1),
                "1", "", SETTLE_MS);
        addStop(EventLog.SVC_002, EventLog.COMP_002, SETTLE_MS);
        addGap(GAP_MS);

        addStart(EventLog.SVC_003, EventLog.COMP_003, intentA(EventLog.COMP_003, "want", 2),
                "want=2", "", SETTLE_MS);
        addStart(EventLog.SVC_003, EventLog.COMP_003, intentA(EventLog.COMP_003, "want", 3),
                "want=3", "", SETTLE_MS);
        addStart(EventLog.SVC_003, EventLog.COMP_003, intentA(EventLog.COMP_003, null, 0),
                "want=none", "", SETTLE_MS);
        addStop(EventLog.SVC_003, EventLog.COMP_003, SETTLE_MS);
        addGap(GAP_MS);

        addStart(EventLog.SVC_004, EventLog.COMP_004, intentA(EventLog.COMP_004, "cmd", "none"),
                "none", "none", SETTLE_MS);
        Intent stopOld = intentA(EventLog.COMP_004, "cmd", "stopOld");
        stopOld.putExtra("oldId", 1);
        addStart(EventLog.SVC_004, EventLog.COMP_004, stopOld, "none", "stopOld", SETTLE_MS);
        addStart(EventLog.SVC_004, EventLog.COMP_004, intentA(EventLog.COMP_004, "cmd", "none"),
                "none", "none", SETTLE_MS);
        addStart(EventLog.SVC_004, EventLog.COMP_004,
                intentA(EventLog.COMP_004, "cmd", "stopSelfNow"), "none", "stopSelfNow", SETTLE_MS);
        addStop(EventLog.SVC_004, EventLog.COMP_004, SETTLE_MS);

        steps.add(new Step(FINAL_MS, new Runnable() {
            @Override
            public void run() {
                log.finalizeRun();
            }
        }));
    }

    private Intent intentA(String component, String extra, int intValue) {
        Intent i = new Intent();
        i.setComponent(ComponentName.unflattenFromString(component));
        i.setAction("org.atl.tests.svc.START");
        if (extra != null) {
            i.putExtra(extra, intValue);
        }
        return i;
    }

    private Intent intentA(String component, String extra, String strValue) {
        Intent i = new Intent();
        i.setComponent(ComponentName.unflattenFromString(component));
        i.setAction("org.atl.tests.svc.START");
        if (extra != null) {
            i.putExtra(extra, strValue);
        }
        return i;
    }

    private void addStart(String scenario, String component, Intent intent,
                          String intentExtra, String cmd, long delay) {
        steps.add(new Step(delay, new StartAction(scenario, component, intent, intentExtra, cmd)));
    }

    private void addStop(String scenario, String component, long delay) {
        steps.add(new Step(delay, new StopAction(scenario, component)));
    }

    private void addGap(long delay) {
        steps.add(new Step(delay, NOP));
    }

    /** Sequential, non-blocking pump: each step is posted, then the next. */
    private void pump() {
        if (cursor >= steps.size()) {
            return;
        }
        final Step step = steps.get(cursor++);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    step.action.run();
                } catch (Throwable t) {
                    log.noteException("step " + cursor + ": " + t);
                }
                pump();
            }
        }, step.delay);
    }

    /** One client step plus its inter-step delay. */
    private static final class Step {
        final long delay;
        final Runnable action;

        Step(long delay, Runnable action) {
            this.delay = delay;
            this.action = action;
        }
    }

    /** startService(A); logs the returned ComponentName (never throws out). */
    private final class StartAction implements Runnable {
        private final String scenario;
        private final String component;
        private final Intent intent;
        private final String intentExtra;
        private final String cmd;

        StartAction(String scenario, String component, Intent intent,
                    String intentExtra, String cmd) {
            this.scenario = scenario;
            this.component = component;
            this.intent = intent;
            this.intentExtra = intentExtra;
            this.cmd = cmd;
        }

        @Override
        public void run() {
            String ret;
            String retType;
            String exc = null;
            try {
                ComponentName cn = startService(intent);
                ret = (cn == null) ? "null" : cn.flattenToShortString();
                retType = (cn == null) ? "null" : "ComponentName";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " startService: " + t);
            }
            log.clientEvent(scenario, "startService", component, intentExtra, cmd,
                    ret, retType, exc);
        }
    }

    /** stopService(A); logs the returned boolean (never throws out). */
    private final class StopAction implements Runnable {
        private final String scenario;
        private final String component;

        StopAction(String scenario, String component) {
            this.scenario = scenario;
            this.component = component;
        }

        @Override
        public void run() {
            Intent i = new Intent();
            i.setComponent(ComponentName.unflattenFromString(component));
            i.setAction("org.atl.tests.svc.START");
            String ret;
            String retType;
            String exc = null;
            try {
                boolean b = stopService(i);
                ret = Boolean.toString(b);
                retType = "boolean";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " stopService: " + t);
            }
            log.clientEvent(scenario, "stopService", component, "none", "", ret, retType, exc);
        }
    }
}
