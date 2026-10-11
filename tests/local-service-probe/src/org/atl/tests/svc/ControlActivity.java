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
    private final List<V3Step> v4steps = new ArrayList<V3Step>();
    private int v4cursor = 0;
    private final List<V3Step> v5steps = new ArrayList<V3Step>();
    private int v5cursor = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("ATL LOCAL-SERVICES probe v4 (SVC-002..016) running");
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(tv);

        log = EventLog.init(this, new String[]{
                EventLog.SVC_002, EventLog.SVC_003, EventLog.SVC_004,
                EventLog.SVC_005, EventLog.SVC_006, EventLog.SVC_007,
                EventLog.SVC_008, EventLog.SVC_009,
                EventLog.SVC_010, EventLog.SVC_011, EventLog.SVC_013, EventLog.SVC_016,
                EventLog.SVC_012, EventLog.SVC_014, EventLog.SVC_015, EventLog.SVC_017, "SVC-018"});
        buildSteps();
        buildV3Steps();
        buildV4Steps();
        buildV5Steps();
        buildIntegrationSteps();
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
            startV4();
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
        /** SVC-013b: unbind from inside onServiceConnected (once). */
        boolean unbindOnConnected;

        Connection(String name) {
            this.name = name;
        }

        Connection(String name, boolean unbindOnConnected) {
            this.name = name;
            this.unbindOnConnected = unbindOnConnected;
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
            if (unbindOnConnected) {
                unbindOnConnected = false;
                String ret = "void";
                String retType = "void";
                String exc = null;
                try {
                    // SVC-013b: re-enter the API from inside the callback.
                    unbindService(this);
                } catch (Throwable t) {
                    ret = "exception:" + t.getClass().getName();
                    retType = "exception";
                    exc = t.getClass().getName();
                    log.noteException("SVC-013b reentrant unbindService: " + t);
                }
                log.clientEventEx(EventLog.SVC_013, "unbindService",
                        cn == null ? "null" : cn.flattenToShortString(),
                        "none", "", name, null, -1, ret, retType, exc);
            }
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

    // ---------------------------------------------------------------- v4 chain

    private void startV4() {
        v4cursor = 0;
        // Deterministic rerun: reset the SVC-013c persistent (static) guard.
        SvcReenterUnbind.resetGuard();
        pumpV4();
    }

    /** Same barrier discipline as the v3 chain: one barrier after each call/burst. */
    private void pumpV4() {
        if (v4cursor >= v4steps.size()) {
            startV5();
            return;
        }
        final V3Step s = v4steps.get(v4cursor++);
        EventLog.setCurrentScenario(s.scenario);
        try {
            s.call.run();
        } catch (Throwable t) {
            log.noteException("v4 step " + v4cursor + ": " + t);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                EventLog.setCurrentScenario(s.scenario);
                try {
                    log.barrierEvent(s.scenario, s.component, s.barrierClient);
                } catch (Throwable t) {
                    log.noteException("v4 barrier: " + t);
                }
                pumpV4();
            }
        }, BARRIER_MS);
    }

    private void addV4(String scenario, String component, String client, Runnable call) {
        v4steps.add(new V3Step(scenario, component, client, call));
    }

    /** bind intent with an explicit action and optional extras (SVC-010 identity). */
    private Intent bindIntent2(String component, String action, int x) {
        Intent i = new Intent();
        i.setComponent(ComponentName.unflattenFromString(component));
        i.setAction(action);
        if (x >= 0) {
            i.putExtra("x", x);
        }
        return i;
    }

    /** start intent with cmd and optional oldId extras (SVC-011/013/016). */
    private Intent startIntentV4(String component, String cmd, int oldId) {
        Intent i = new Intent();
        i.setComponent(ComponentName.unflattenFromString(component));
        i.setAction("org.atl.tests.svc.START");
        if (cmd != null) {
            i.putExtra("cmd", cmd);
        }
        if (oldId >= 0) {
            i.putExtra("oldId", oldId);
        }
        return i;
    }

    private void buildV4Steps() {
        // ---- SVC-010: duplicate bindings, extras vs action identity, invalid unbind
        {
            final String comp = EventLog.COMP_V4B;
            Connection c1 = new Connection("c1");
            Connection c2 = new Connection("c2");
            addV4(EventLog.SVC_010, comp, "c1", new BindIntentCall(EventLog.SVC_010, comp,
                    c1, "c1", bindIntent2(comp, "org.atl.tests.svc.BIND", -1),
                    Context.BIND_AUTO_CREATE, "Ia"));
            addV4(EventLog.SVC_010, comp, "c1", new BindIntentCall(EventLog.SVC_010, comp,
                    c1, "c1", bindIntent2(comp, "org.atl.tests.svc.BIND", -1),
                    Context.BIND_AUTO_CREATE, "Ia"));
            addV4(EventLog.SVC_010, comp, "c1", new BindIntentCall(EventLog.SVC_010, comp,
                    c1, "c1", bindIntent2(comp, "org.atl.tests.svc.BIND", 1),
                    Context.BIND_AUTO_CREATE, "Ib"));
            addV4(EventLog.SVC_010, comp, "c1", new BindIntentCall(EventLog.SVC_010, comp,
                    c1, "c1", bindIntent2(comp, "org.atl.tests.svc.BIND2", -1),
                    Context.BIND_AUTO_CREATE, "Ic"));
            addV4(EventLog.SVC_010, comp, "c2", new UnbindCallV4(EventLog.SVC_010, comp, c2, "c2"));
            addV4(EventLog.SVC_010, comp, "c1", new UnbindCallV4(EventLog.SVC_010, comp, c1, "c1"));
            addV4(EventLog.SVC_010, comp, "c1", new UnbindCallV4(EventLog.SVC_010, comp, c1, "c1"));
        }
        // ---- SVC-011: rapid bursts, one barrier per burst
        {
            final String comp = EventLog.COMP_V4B;
            Connection c1 = new Connection("c1");
            addV4(EventLog.SVC_011, comp, null, new BurstCall(
                    new StartCallV4(EventLog.SVC_011, comp, "none", -1),
                    new StopCallV4(EventLog.SVC_011, comp),
                    new StartCallV4(EventLog.SVC_011, comp, "none", -1),
                    new StopCallV4(EventLog.SVC_011, comp)));
            addV4(EventLog.SVC_011, comp, "c1", new BurstCall(
                    new BindCall(EventLog.SVC_011, comp, c1, Context.BIND_AUTO_CREATE),
                    new UnbindCallV4(EventLog.SVC_011, comp, c1, "c1"),
                    new BindCall(EventLog.SVC_011, comp, c1, Context.BIND_AUTO_CREATE),
                    new UnbindCallV4(EventLog.SVC_011, comp, c1, "c1")));
            addV4(EventLog.SVC_011, comp, "c1", new BurstCall(
                    new StartCallV4(EventLog.SVC_011, comp, "none", -1),
                    new BindCall(EventLog.SVC_011, comp, c1, Context.BIND_AUTO_CREATE),
                    new StopCallV4(EventLog.SVC_011, comp),
                    new UnbindCallV4(EventLog.SVC_011, comp, c1, "c1")));
        }
        // ---- SVC-013: reentrant calls from callbacks
        {
            // 13a — service re-enters startService from onStartCommand.
            addV4(EventLog.SVC_013, EventLog.COMP_RE_START, null,
                    new StartCallV4(EventLog.SVC_013, EventLog.COMP_RE_START, "reenter", -1));
            addV4(EventLog.SVC_013, EventLog.COMP_RE_START, null,
                    new StopCallV4(EventLog.SVC_013, EventLog.COMP_RE_START));

            // 13b — client unbinds itself from onServiceConnected.
            Connection cx = new Connection("c1", true);
            addV4(EventLog.SVC_013, EventLog.COMP_RE_BIND, "c1", new BindCall(
                    EventLog.SVC_013, EventLog.COMP_RE_BIND, cx, Context.BIND_AUTO_CREATE));

            // 13c — service re-enters bindService from onUnbind; driver tears c2 down.
            Connection c1 = new Connection("c1");
            addV4(EventLog.SVC_013, EventLog.COMP_RE_UNBIND, "c1", new BindCall(
                    EventLog.SVC_013, EventLog.COMP_RE_UNBIND, c1, Context.BIND_AUTO_CREATE));
            addV4(EventLog.SVC_013, EventLog.COMP_RE_UNBIND, "c1", new UnbindCallV4(
                    EventLog.SVC_013, EventLog.COMP_RE_UNBIND, c1, "c1"));
            addV4(EventLog.SVC_013, EventLog.COMP_RE_UNBIND, "c2",
                    new UnbindC2Call(EventLog.SVC_013, EventLog.COMP_RE_UNBIND));
        }
        // ---- SVC-016: stopSelfResult/stopSelf with the CURRENT start id
        {
            final String comp = EventLog.COMP_STOP_SELF;
            addV4(EventLog.SVC_016, comp, null,
                    new StartCallV4(EventLog.SVC_016, comp, "stopSelfResultCurrent", -1));
            addV4(EventLog.SVC_016, comp, null, new StopCallV4(EventLog.SVC_016, comp));
            addV4(EventLog.SVC_016, comp, null,
                    new StartCallV4(EventLog.SVC_016, comp, "none", -1));
            addV4(EventLog.SVC_016, comp, null,
                    new StartCallV4(EventLog.SVC_016, comp, "stopSelfIntOld", 1));
            addV4(EventLog.SVC_016, comp, null,
                    new StartCallV4(EventLog.SVC_016, comp, "none", -1));
            addV4(EventLog.SVC_016, comp, null,
                    new StartCallV4(EventLog.SVC_016, comp, "stopSelfIntCurrent", -1));
            addV4(EventLog.SVC_016, comp, null, new StopCallV4(EventLog.SVC_016, comp));
        }
    }

    // ---------------------------------------------------------- v4 call actions

    /** bindService with an explicit intent; logs the return and identity label. */
    private final class BindIntentCall implements Runnable {
        private final String scenario;
        private final String component;
        private final ServiceConnection conn;
        private final String client;
        private final Intent intent;
        private final int flags;
        private final String label;

        BindIntentCall(String scenario, String component, ServiceConnection conn,
                       String client, Intent intent, int flags, String label) {
            this.scenario = scenario;
            this.component = component;
            this.conn = conn;
            this.client = client;
            this.intent = intent;
            this.flags = flags;
            this.label = label;
        }

        @Override
        public void run() {
            String ret;
            String retType = "boolean";
            String exc = null;
            try {
                ret = Boolean.toString(bindService(intent, conn, flags));
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " bindService(" + label + "): " + t);
            }
            log.clientEventEx(scenario, "bindService", component, label, "",
                    client, null, flags, ret, retType, exc);
        }
    }

    /** unbindService(ServiceConnection) with an explicit client id. */
    private final class UnbindCallV4 implements Runnable {
        private final String scenario;
        private final String component;
        private final ServiceConnection conn;
        private final String client;

        UnbindCallV4(String scenario, String component, ServiceConnection conn, String client) {
            this.scenario = scenario;
            this.component = component;
            this.conn = conn;
            this.client = client;
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
                log.noteException(scenario + " unbindService(" + client + "): " + t);
            }
            log.clientEventEx(scenario, "unbindService", component, "none", "",
                    client, null, -1, ret, retType, exc);
        }
    }

    /** Tears down the service-owned SVC-013c connection (resolved at run time). */
    private final class UnbindC2Call implements Runnable {
        private final String scenario;
        private final String component;

        UnbindC2Call(String scenario, String component) {
            this.scenario = scenario;
            this.component = component;
        }

        @Override
        public void run() {
            ServiceConnection conn = SvcReenterUnbind.c2();
            String ret;
            String retType;
            String exc = null;
            if (conn == null) {
                ret = "null-connection";
                retType = "null";
            } else {
                ret = "void";
                retType = "void";
                try {
                    unbindService(conn);
                } catch (Throwable t) {
                    ret = "exception:" + t.getClass().getName();
                    retType = "exception";
                    exc = t.getClass().getName();
                    log.noteException(scenario + " unbindService(c2): " + t);
                }
            }
            log.clientEventEx(scenario, "unbindService", component, "none", "",
                    "c2", null, -1, ret, retType, exc);
        }
    }

    /** startService with cmd/oldId extras; logs the return and extras. */
    private final class StartCallV4 implements Runnable {
        private final String scenario;
        private final String component;
        private final String cmd;
        private final int oldId;

        StartCallV4(String scenario, String component, String cmd, int oldId) {
            this.scenario = scenario;
            this.component = component;
            this.cmd = cmd;
            this.oldId = oldId;
        }

        @Override
        public void run() {
            String ret;
            String retType;
            String exc = null;
            try {
                ComponentName cn = startService(startIntentV4(component, cmd, oldId));
                ret = (cn == null) ? "null" : cn.flattenToShortString();
                retType = (cn == null) ? "null" : "ComponentName";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException(scenario + " startService: " + t);
            }
            log.clientEventEx(scenario, "startService", component,
                    (cmd == null) ? "none" : "cmd=" + cmd,
                    (cmd == null) ? "" : cmd, null, null, -1, ret, retType, exc);
        }
    }

    /** stopService(component) logging the returned boolean. */
    private final class StopCallV4 implements Runnable {
        private final String scenario;
        private final String component;

        StopCallV4(String scenario, String component) {
            this.scenario = scenario;
            this.component = component;
        }

        @Override
        public void run() {
            String ret;
            String retType = "boolean";
            String exc = null;
            try {
                ret = Boolean.toString(stopService(intentA(component, null, 0)));
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

    /** Runs several API calls with NO barrier inside the burst. */
    private final class BurstCall implements Runnable {
        private final Runnable[] calls;

        BurstCall(Runnable... calls) {
            this.calls = calls;
        }

        @Override
        public void run() {
            for (int i = 0; i < calls.length; i++) {
                try {
                    calls[i].run();
                } catch (Throwable t) {
                    log.noteException("burst call " + i + ": " + t);
                }
            }
        }
    }

    // ---------------------------------------------------------------- v5 chain

    private void startV5() {
        v5cursor = 0;
        pumpV5();
    }

    /**
     * v5 chain: same barrier discipline as v3/v4 (one barrier after each step).
     * {@code pendingCallThread} is cleared before every step so non-SVC-012
     * callbacks report the main thread as their issuing thread; a {@link BgCall}
     * step re-annotates the issuing thread as "bg" while it runs.
     */
    private void pumpV5() {
        if (v5cursor >= v5steps.size()) {
            log.finalizeRun();
            return;
        }
        final V3Step s = v5steps.get(v5cursor++);
        EventLog.setCurrentScenario(s.scenario);
        EventLog.setCallThread(null);
        try {
            s.call.run();
        } catch (Throwable t) {
            log.noteException("v5 step " + v5cursor + ": " + t);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                EventLog.setCurrentScenario(s.scenario);
                try {
                    log.barrierEvent(s.scenario, s.component, s.barrierClient);
                } catch (Throwable t) {
                    log.noteException("v5 barrier: " + t);
                }
                pumpV5();
            }
        }, BARRIER_MS);
    }

    private void addV5(String scenario, String component, String client, Runnable call) {
        v5steps.add(new V3Step(scenario, component, client, call));
    }

    private void buildV5Steps() {
        // SVC-012: every API call issued from a background thread, joined before
        // the next barrier. Component "A" here is the full-lifecycle SvcV4B class
        // (logs onCreate/onStartCommand/onBind/onUnbind/onDestroy).
        {
            final String comp = EventLog.COMP_V4B;
            Connection c1 = new Connection("c1");
            addV5(EventLog.SVC_012, comp, "c1",
                    new BgCall(new StartCallV4(EventLog.SVC_012, comp, "none", -1)));
            addV5(EventLog.SVC_012, comp, "c1", new BgCall(new BindCall(
                    EventLog.SVC_012, comp, c1, Context.BIND_AUTO_CREATE)));
            addV5(EventLog.SVC_012, comp, "c1", new BgCall(new UnbindCallV4(
                    EventLog.SVC_012, comp, c1, "c1")));
            // step 4: bg thread startService then stopService, NO join between.
            addV5(EventLog.SVC_012, comp, null, new BgCall(new BurstCall(
                    new StartCallV4(EventLog.SVC_012, comp, "none", -1),
                    new StopCallV4(EventLog.SVC_012, comp))));
            // step 5: bg bind, then main-thread unbind (mixed-thread teardown).
            addV5(EventLog.SVC_012, comp, "c1", new BgCall(new BindCall(
                    EventLog.SVC_012, comp, c1, Context.BIND_AUTO_CREATE)));
            addV5(EventLog.SVC_012, comp, "c1", new UnbindCallV4(
                    EventLog.SVC_012, comp, c1, "c1"));
        }
        // SVC-014a: unknown/undeclared component (parity: null/false/false).
        {
            final String comp = EventLog.COMP_UNKNOWN;
            Connection c1 = new Connection("c1");
            addV5(EventLog.SVC_014, comp, null,
                    new StartCallV4(EventLog.SVC_014, comp, "none", -1));
            addV5(EventLog.SVC_014, comp, "c1", new BindCall(
                    EventLog.SVC_014, comp, c1, Context.BIND_AUTO_CREATE));
            addV5(EventLog.SVC_014, comp, null, new StopCallV4(EventLog.SVC_014, comp));
        }
        // SVC-014b: service declared android:process=":remote".
        {
            final String comp = EventLog.COMP_REMOTE;
            Connection c2 = new Connection("c2");
            addV5(EventLog.SVC_014, comp, null,
                    new StartCallV4(EventLog.SVC_014, comp, "none", -1));
            addV5(EventLog.SVC_014, comp, "c2", new BindCall(
                    EventLog.SVC_014, comp, c2, Context.BIND_AUTO_CREATE));
            addV5(EventLog.SVC_014, comp, null, new StopCallV4(EventLog.SVC_014, comp));
        }
        // SVC-015: N=5 started cycles, N=5 bound cycles, then the final state probe.
        {
            final String comp = EventLog.COMP_CYCLES;
            for (int i = 0; i < 5; i++) {
                addV5(EventLog.SVC_015, comp, null,
                        new StartCallV4(EventLog.SVC_015, comp, "none", -1));
                addV5(EventLog.SVC_015, comp, null, new StopCallV4(EventLog.SVC_015, comp));
            }
            Connection c1 = new Connection("c1");
            for (int i = 0; i < 5; i++) {
                addV5(EventLog.SVC_015, comp, "c1", new BindCall(
                        EventLog.SVC_015, comp, c1, Context.BIND_AUTO_CREATE));
                addV5(EventLog.SVC_015, comp, "c1", new UnbindCallV4(
                        EventLog.SVC_015, comp, c1, "c1"));
            }
            addV5(EventLog.SVC_015, comp, null,
                    new StartCallV4(EventLog.SVC_015, comp, "none", -1));
            addV5(EventLog.SVC_015, comp, null, new StopCallV4(EventLog.SVC_015, comp));
            // c1 already unbound: expect IllegalArgumentException (logged, continue).
            addV5(EventLog.SVC_015, comp, "c1", new UnbindCallV4(
                    EventLog.SVC_015, comp, c1, "c1"));
        }
        // SVC-017: started-then-bound instance with onUnbind()=true + onRebind().
        {
            final String comp = EventLog.COMP_BTS;
            Connection c1 = new Connection("c1");
            Connection c2 = new Connection("c2");
            addV5(EventLog.SVC_017, comp, null,
                    new StartCallV4(EventLog.SVC_017, comp, "none", -1));
            addV5(EventLog.SVC_017, comp, "c1", new BindCall(
                    EventLog.SVC_017, comp, c1, Context.BIND_AUTO_CREATE));
            addV5(EventLog.SVC_017, comp, "c1", new UnbindCallV4(
                    EventLog.SVC_017, comp, c1, "c1"));
            addV5(EventLog.SVC_017, comp, "c2", new BindCall(
                    EventLog.SVC_017, comp, c2, Context.BIND_AUTO_CREATE));
            addV5(EventLog.SVC_017, comp, "c2", new UnbindCallV4(
                    EventLog.SVC_017, comp, c2, "c2"));
            addV5(EventLog.SVC_017, comp, null, new StopCallV4(EventLog.SVC_017, comp));
            addV5(EventLog.SVC_017, comp, null, new StopCallV4(EventLog.SVC_017, comp));
        }
    }


    public static class IntegrationFragment extends android.app.Fragment {}
    private void buildIntegrationSteps() {
        final String sid = "SVC-018", comp = EventLog.PKG + "/.SvcIdentity";
        final String[] received = new String[2];
        final int[] deliveries = new int[2];
        final int[] disconnects = new int[2];
        final ServiceConnection[] clients = new ServiceConnection[2];
        for (int i = 0; i < 2; i++) {
            final int n = i;
            clients[i] = new ServiceConnection() {
                public void onServiceConnected(ComponentName c, IBinder binder) {
                    try { received[n] = binder.getInterfaceDescriptor(); deliveries[n]++; }
                    catch (Throwable e) { log.noteException("descriptor: " + e); }
                }
                public void onServiceDisconnected(ComponentName c) { disconnects[n]++; }
            };
        }
        final Intent a = new Intent(this, SvcIdentity.class).setAction("identity-a");
        final Intent b = new Intent(this, SvcIdentity.class).setAction("identity-b");
        addV5(sid, comp, null, new Runnable() { public void run() {
            android.app.FragmentManager fm = getFragmentManager();
            android.app.Fragment f = new IntegrationFragment();
            android.app.FragmentTransaction tx = fm.beginTransaction().add(f, "integration");
            int result = tx.commit();
            checkIntegration(result == -1, "no back-stack identifier");
            checkIntegration(fm.findFragmentByTag("integration") == null, "commit must queue");
            checkIntegration(fm.executePendingTransactions(), "pending transaction executed");
            checkIntegration(fm.findFragmentByTag("integration") == f, "fragment found after execute");
            boolean rejected = false;
            try { tx.commit(); } catch (IllegalStateException expected) { rejected = true; }
            checkIntegration(rejected, "second commit rejected");
            fm.beginTransaction().remove(f).commit(); fm.executePendingTransactions();
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {checkIntegration(bindService(a, clients[0], 0), "bind pending");} });
        addV5(sid, comp, null, new Runnable() { public void run() {checkIntegration(bindService(b, clients[1], BIND_AUTO_CREATE), "bind auto");} });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration("identity-a".equals(received[0]), "first identity binder");
            checkIntegration("identity-b".equals(received[1]), "second identity binder");
            startService(a);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {SvcIdentity.instance.stopSelf();} });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(SvcIdentity.destroyed == 0, "self-stop preserves bound service");
            unbindService(clients[0]);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {checkIntegration(bindService(a, clients[0], BIND_AUTO_CREATE), "reuse connection");} });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(deliveries[0] == 2, "connection receives binder after rebinding");
            unbindService(clients[0]); unbindService(clients[1]);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {checkIntegration(SvcIdentity.destroyed == 1, "last unbind destroys once");} });
        addV5(sid, comp, null, new Runnable() { public void run() {
            deliveries[0] = 0; disconnects[0] = 0;
            bindService(a, clients[0], BIND_AUTO_CREATE);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() { bindService(b, clients[0], BIND_AUTO_CREATE); } });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(deliveries[0] == 2 && disconnects[0] == 1 && "identity-b".equals(received[0]),
                    "same connection receives replacement binder");
            unbindService(clients[0]);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {
            deliveries[0] = 0;
            bindService(a, clients[0], BIND_AUTO_CREATE);
            getApplicationContext().bindService(a, clients[0], BIND_AUTO_CREATE);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(deliveries[0] == 2, "contexts have independent dispatchers");
            getApplicationContext().unbindService(clients[0]);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(SvcIdentity.destroyed == 0, "other context keeps binding");
            unbindService(clients[0]);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(SvcIdentity.destroyed == 1, "last context destroys service");
            bindService(a, clients[0], BIND_AUTO_CREATE);
            bindService(new Intent(ControlActivity.this, SvcIdentityOther.class), clients[0], BIND_AUTO_CREATE);
        } });
        addV5(sid, comp, null, new Runnable() { public void run() { unbindService(clients[0]); } });
        addV5(sid, comp, null, new Runnable() { public void run() {
            checkIntegration(SvcIdentity.destroyed == 2, "unbind removes all components");
        } });

    }
    private void checkIntegration(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        log.clientEvent("SVC-018", "check", EventLog.PKG + "/.SvcIdentity", "none", label, "true", "boolean", null);
    }

    /**
     * Runs its body on a fresh background thread named "bg" and joins it before
     * returning (the pump then posts the barrier). The issuing thread is
     * annotated as "bg" so every callback caused by these calls reports
     * {@code callThread="bg"} while {@code thread} stays the callback thread.
     */
    private final class BgCall implements Runnable {
        private final Runnable body;

        BgCall(Runnable body) {
            this.body = body;
        }

        @Override
        public void run() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    EventLog.setCallThread("bg");
                    try {
                        body.run();
                    } catch (Throwable x) {
                        log.noteException("bg call: " + x);
                    }
                }
            }, "bg");
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
