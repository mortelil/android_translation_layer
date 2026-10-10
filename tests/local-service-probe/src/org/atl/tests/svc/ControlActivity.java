/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * LOCAL-SERVICES probe v3 driver: runs SVC-002..SVC-004 (preserved v2 code) then
 * the appended SVC-005..SVC-009 binding scenarios, in order on the main thread,
 * and emits ONE aggregate (getFilesDir()/results.json + the single
 * SVC_JSON_BEGIN/SVC_JSON_END stdout block) after all of them finish.
 *
 * Key properties (per the assignment):
 *  - The Activity stays alive for the whole run (never finishes itself).
 *  - The probe NEVER blocks waiting for a Service callback; every platform call
 *    is wrapped in try/catch and logs an {@code exception} event on failure.
 *  - v2 steps keep their delay-based pump; v3 steps additionally post a no-op
 *    Runnable to the main Looper after each API call ("barrier"), so the
 *    asynchronously delivered connection callbacks are logged in deterministic
 *    order before the next step, and the barrier itself is logged as an event.
 *  - A bounded watchdog forces the aggregate so the single end marker is always
 *    emitted even if a platform delivers no callbacks.
 *
 * Fixed inputs:
 *  SVC-002 — startService(A, k=1),(k=2),(k=1), stopService(A)
 *  SVC-003 — startService(A, want=2),(want=3),(want=none), stopService(A)
 *  SVC-004 — startService(A, cmd=none),(stopOld,oldId=1),(none),(stopSelfNow), stopService(A)
 *  SVC-005 — bind B c1 AUTO, bind B c2 AUTO, unbind c1, unbind c2
 *  SVC-006 — bind B c1 (no AUTO_CREATE), bind B c2 AUTO, unbind c1, unbind c2
 *  SVC-007 — startService(B), bind B c1 AUTO, stopService(B), unbind c1, stopService(B)
 *  SVC-008 — 008a Bf: bind c1, unbind c1, bind c2, unbind c2
 *            008b Bt: bind c1, unbind c1, bind c2, unbind c2
 *  SVC-009 — bind Bn c1 AUTO, unbind c1
 */
public class ControlActivity extends Activity {
    /** Delay before the very first client step, to let the window settle. */
    static final long INITIAL_MS = 500L;
    /** Delay between v2 client steps, to let async Service callbacks land. */
    static final long SETTLE_MS = 700L;
    /** Extra pause between v2 scenarios (allows onDestroy / next instance). */
    static final long GAP_MS = 1400L;
    /** Barrier delay: posted no-op Runnable per v3 call (queue drain window). */
    static final long BARRIER_MS = 400L;
    /** Hard bound: force the end marker if the step chains have not finished. */
    static final long WATCHDOG_MS = 120000L;

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
    private final List<V3Step> v3steps = new ArrayList<V3Step>();
    private int v3cursor = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("ATL LOCAL-SERVICES probe v3 (SVC-002..009) running");
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(tv);

        log = EventLog.init(this, new String[]{
                EventLog.SVC_002, EventLog.SVC_003, EventLog.SVC_004,
                EventLog.SVC_005, EventLog.SVC_006, EventLog.SVC_007,
                EventLog.SVC_008, EventLog.SVC_009});
        buildSteps();
        buildV3Steps();
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

    // ---------------------------------------------------------------- v2 chain

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
        addGap(GAP_MS);
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
            startV3();
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

    // ---------------------------------------------------------------- v3 chain

    private void startV3() {
        v3cursor = 0;
        pumpV3();
    }

    /**
     * v3 chain: run the API call, then post a no-op Runnable to the main Looper;
     * when it runs the queue has drained, so the barrier event is logged here and
     * the next step is scheduled only afterwards.
     */
    private void pumpV3() {
        if (v3cursor >= v3steps.size()) {
            log.finalizeRun();
            return;
        }
        final V3Step s = v3steps.get(v3cursor++);
        EventLog.setCurrentScenario(s.scenario);
        try {
            s.call.run();
        } catch (Throwable t) {
            log.noteException("v3 step " + v3cursor + ": " + t);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                EventLog.setCurrentScenario(s.scenario);
                try {
                    log.barrierEvent(s.scenario, s.component, s.barrierClient);
                } catch (Throwable t) {
                    log.noteException("barrier: " + t);
                }
                pumpV3();
            }
        }, BARRIER_MS);
    }

    private Intent bindIntent(String component) {
        Intent i = new Intent();
        i.setComponent(ComponentName.unflattenFromString(component));
        i.setAction("org.atl.tests.svc.BIND");
        return i;
    }

    private void addBind(String scenario, String component, Connection conn, int flags) {
        v3steps.add(new V3Step(scenario, component, conn.name,
                new BindCall(scenario, component, conn, flags)));
    }

    private void addUnbind(String scenario, String component, Connection conn) {
        v3steps.add(new V3Step(scenario, component, conn.name,
                new UnbindCall(scenario, component, conn)));
    }

    private void addSvcStart(String scenario, String component) {
        v3steps.add(new V3Step(scenario, component, null,
                new StartCall(scenario, component)));
    }

    private void addSvcStop(String scenario, String component) {
        v3steps.add(new V3Step(scenario, component, null,
                new StopCall(scenario, component)));
    }

    private void buildV3Steps() {
        // SVC-005: bound-only, two clients.
        {
            Connection c1 = new Connection("c1");
            Connection c2 = new Connection("c2");
            addBind(EventLog.SVC_005, EventLog.COMP_B, c1, Context.BIND_AUTO_CREATE);
            addBind(EventLog.SVC_005, EventLog.COMP_B, c2, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_005, EventLog.COMP_B, c1);
            addUnbind(EventLog.SVC_005, EventLog.COMP_B, c2);
        }
        // SVC-006: c1 without AUTO_CREATE, c2 with.
        {
            Connection c1 = new Connection("c1");
            Connection c2 = new Connection("c2");
            addBind(EventLog.SVC_006, EventLog.COMP_B, c1, 0);
            addBind(EventLog.SVC_006, EventLog.COMP_B, c2, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_006, EventLog.COMP_B, c1);
            addUnbind(EventLog.SVC_006, EventLog.COMP_B, c2);
        }
        // SVC-007: started + bound combined lifetime.
        {
            Connection c1 = new Connection("c1");
            addSvcStart(EventLog.SVC_007, EventLog.COMP_B);
            addBind(EventLog.SVC_007, EventLog.COMP_B, c1, Context.BIND_AUTO_CREATE);
            addSvcStop(EventLog.SVC_007, EventLog.COMP_B);
            addUnbind(EventLog.SVC_007, EventLog.COMP_B, c1);
            addSvcStop(EventLog.SVC_007, EventLog.COMP_B);
        }
        // SVC-008a: element Bf, onUnbind -> false (fresh instance each bind).
        {
            Connection c1 = new Connection("c1");
            Connection c2 = new Connection("c2");
            addBind(EventLog.SVC_008, EventLog.COMP_BF, c1, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_008, EventLog.COMP_BF, c1);
            addBind(EventLog.SVC_008, EventLog.COMP_BF, c2, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_008, EventLog.COMP_BF, c2);
        }
        // SVC-008b: element Bt, onUnbind -> true + onRebind (instance survives).
        {
            Connection c1 = new Connection("c1");
            Connection c2 = new Connection("c2");
            addBind(EventLog.SVC_008, EventLog.COMP_BT, c1, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_008, EventLog.COMP_BT, c1);
            addBind(EventLog.SVC_008, EventLog.COMP_BT, c2, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_008, EventLog.COMP_BT, c2);
        }
        // SVC-009: element Bn, onBind returns null.
        {
            Connection c1 = new Connection("c1");
            addBind(EventLog.SVC_009, EventLog.COMP_BN, c1, Context.BIND_AUTO_CREATE);
            addUnbind(EventLog.SVC_009, EventLog.COMP_BN, c1);
        }
    }

    /** One v3 call plus the barrier that follows it. */
    private static final class V3Step {
        final String scenario;
        final String component;
        final String barrierClient;
        final Runnable call;

        V3Step(String scenario, String component, String barrierClient, Runnable call) {
            this.scenario = scenario;
            this.component = component;
            this.barrierClient = barrierClient;
            this.call = call;
        }
    }

    // ---------------------------------------------------------- v2 call actions

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

    // ---------------------------------------------------------- v3 call actions

    /** bindService(component, conn, flags); logs the returned boolean. */
    private final class BindCall implements Runnable {
        private final String scenario;
        private final String component;
        private final Connection conn;
        private final int flags;

        BindCall(String scenario, String component, Connection conn, int flags) {
            this.scenario = scenario;
            this.component = component;
            this.conn = conn;
            this.flags = flags;
        }

        @Override
        public void run() {
            String ret;
            String retType = "boolean";
            String exc = null;
            try {
                boolean b = bindService(bindIntent(component), conn, flags);
                ret = Boolean.toString(b);
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " bindService: " + t);
            }
            log.clientEventEx(scenario, "bindService", component, "none", "",
                    conn.name, null, flags, ret, retType, exc);
        }
    }

    /** unbindService(conn); void, logs "void" (never throws out). */
    private final class UnbindCall implements Runnable {
        private final String scenario;
        private final String component;
        private final Connection conn;

        UnbindCall(String scenario, String component, Connection conn) {
            this.scenario = scenario;
            this.component = component;
            this.conn = conn;
        }

        @Override
        public void run() {
            String ret = "void";
            String retType = "void";
            String exc = null;
            try {
                unbindService(conn);
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " unbindService: " + t);
            }
            log.clientEventEx(scenario, "unbindService", component, "none", "",
                    conn.name, null, -1, ret, retType, exc);
        }
    }

    /** startService(component) inside the v3 chain (SVC-007). */
    private final class StartCall implements Runnable {
        private final String scenario;
        private final String component;

        StartCall(String scenario, String component) {
            this.scenario = scenario;
            this.component = component;
        }

        @Override
        public void run() {
            String ret;
            String retType;
            String exc = null;
            try {
                ComponentName cn = startService(intentA(component, null, 0));
                ret = (cn == null) ? "null" : cn.flattenToShortString();
                retType = (cn == null) ? "null" : "ComponentName";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " startService: " + t);
            }
            log.clientEventEx(scenario, "startService", component, "none", "",
                    null, null, -1, ret, retType, exc);
        }
    }

    /** stopService(component) inside the v3 chain (SVC-007). */
    private final class StopCall implements Runnable {
        private final String scenario;
        private final String component;

        StopCall(String scenario, String component) {
            this.scenario = scenario;
            this.component = component;
        }

        @Override
        public void run() {
            String ret;
            String retType;
            String exc = null;
            try {
                boolean b = stopService(intentA(component, null, 0));
                ret = Boolean.toString(b);
                retType = "boolean";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " stopService: " + t);
            }
            log.clientEventEx(scenario, "stopService", component, "none", "",
                    null, null, -1, ret, retType, exc);
        }
    }

    /**
     * Client-side ServiceConnection. Logs every callback with the connection id
     * and the identity hash of the delivered IBinder; never logs raw addresses.
     */
    private final class Connection implements ServiceConnection {
        final String name;

        Connection(String name) {
            this.name = name;
        }

        private void ev(String event, ComponentName cn, IBinder service,
                        String ret, String retType) {
            log.clientEventEx(EventLog.currentScenario(), event,
                    cn == null ? "null" : cn.flattenToShortString(),
                    "none", "", name,
                    service == null ? null : Integer.toString(System.identityHashCode(service)),
                    -1, ret, retType, null);
        }

        @Override
        public void onServiceConnected(ComponentName cn, IBinder service) {
            ev("onServiceConnected", cn, service, "connected", "IBinder");
        }

        @Override
        public void onServiceDisconnected(ComponentName cn) {
            ev("onServiceDisconnected", cn, null, "disconnected", "void");
        }

        @Override
        public void onNullBinding(ComponentName cn) {
            ev("onNullBinding", cn, null, "null", "void");
        }

        @Override
        public void onBindingDied(ComponentName cn) {
            ev("onBindingDied", cn, null, "died", "void");
        }
    }
}
