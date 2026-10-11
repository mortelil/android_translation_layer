/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

/**
 * ACTIVITY-LIFECYCLE / FRAGMENT-TAG probe v2 (one frozen APK, one process
 * launch). Extends v1 with the LC snapshot/register-during-dispatch/throw and
 * equals-removal scenarios (L-SNAP, L-REGD, L-THROW, L-EQUALS) and the FT
 * FragmentManager tag scenarios (F-ADD-TAG .. F-REMOVE).
 *
 * Scenarios, self-driven from the main thread with no intent extras so Android
 * and ATL launch identically:
 *   INIT   app-registers AppObs-A then AppObs-B (preamble).
 *   S1     LIFE-001 fresh ActA create/start/resume.
 *   S2     LIFE-002 ActA -> ActB -> ActA transition.
 *   S4.1-5 LIFE-004 multiplicities A+B / A only / C twice / C once / none.
 *   S3D1   LIFE-003 app-initiated finish() from the resumed state.
 *   L-SNAP unregister a callback from inside a dispatch (snapshot).
 *   L-REGD register a callback from inside a dispatch.
 *   L-THROW a callback throws; the remaining callbacks are skipped.
 *   L-EQUALS unregister a distinct-but-equal instance removes the registered one.
 *   F-*    FragmentManager tag lookup matrix (see FragmentProbe).
 *   S3D2   external normal close, awaited after LIFE_ALL_DONE.
 */
public class ProbeApp extends Application {
    public static final String PKG = "org.atl.tests.lifecycle";
    public static final String TASK_ID = "ACTIVITY-LIFECYCLE";
    public static final String PROBE_ID = "lifecycle-probe-v2.1";

    private static final int PH_S1 = 1;
    private static final int PH_S2 = 2;
    private static final int PH_S4 = 3;
    private static final int PH_D1 = 4;
    private static final int PH_LCX = 5;
    private static final int PH_FT = 6;
    private static final int PH_D2 = 7;

    private static final int S4_STEPS = 5;
    private static final int LCX_STEPS = 3;
    private static final String TERMINAL_MARK = "LIFE_PROBE_MARK TERMINAL_THROW_DONE";
    private static final long SETTLE_MS = 150L;

    private static ProbeApp instance;

    private Trace trace;
    private Handler handler;
    private Observer obsA;
    private Observer obsB;
    private Observer obsC;
    private ActA currentA;
    private int phase;
    private int s4Step;
    private int lcxStep;
    private String runId;
    private boolean finalized;
    private boolean terminalArmed;

    // LC-extension actors (created per step)
    private Observer obsMut;
    private Observer obsVict;
    private Observer obsReg;
    private Observer obsNew;
    private Observer obsThrow;
    private Observer obsPost;
    private EqualsObserver obsEq1;
    private EqualsObserver obsEq2;

    static ProbeApp get() {
        return instance;
    }

    static Trace trace() {
        return instance == null ? null : instance.trace;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        runId = "lv21-" + Long.toHexString(System.currentTimeMillis())
                + "-" + android.os.Process.myPid();
        try {
            trace = new Trace(getFilesDir(), runId, TASK_ID);
        } catch (Exception e) {
            throw new RuntimeException("trace init failed", e);
        }
        installUncaughtHandler();

        trace.nonScenarioEvent("INIT", "ProbeApp", "appOnCreate", "-", "-", "-");

        obsA = new Observer("AppObs-A");
        obsB = new Observer("AppObs-B");
        obsC = new Observer("AppObs-C");

        registerActivityLifecycleCallbacks(obsA);
        trace.nonScenarioEvent("INIT", "ProbeApp", "registerCallback", "-", "-", "-");
        registerActivityLifecycleCallbacks(obsB);
        trace.nonScenarioEvent("INIT", "ProbeApp", "registerCallback", "-", "-", "-");

        currentA = null;
        phase = PH_S1;
        s4Step = 0;
        lcxStep = 0;
        trace.scenBegin("S1");
    }

    private void installUncaughtHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    if (trace != null) {
                        trace.recordException(e.getClass().getName() + ": " + e.getMessage()
                                + " (thread=" + t.getName() + ")");
                    }
                } catch (Throwable ignored) {
                    // never mask the original crash
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            }
        });
    }

    // ---- Activity hooks (deferred to the next main-loop message) ----

    void onActAResumed(ActA a) {
        currentA = a;
        handler.post(new Runnable() {
            @Override
            public void run() {
                handleAResumed();
            }
        });
    }

    void onActBResumed(final ActB b) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                handleBResumed(b);
            }
        });
    }

    void onActBDestroyed() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                handleBDestroyed();
            }
        });
    }

    void onActADestroyed() {
        if (phase == PH_D2 && trace != null) {
            trace.scenEnd("S3D2");
        }
    }

    private void handleAResumed() {
        if (phase == PH_S1) {
            trace.scenEnd("S1");
            phase = PH_S2;
            trace.scenBegin("S2");
            scheduleStartB();
        }
    }

    private void handleBResumed(ActB b) {
        if (phase == PH_S2 || phase == PH_S4 || phase == PH_D1 || phase == PH_LCX) {
            scheduleFinishB(b);
        }
    }

    private void handleBDestroyed() {
        if (phase == PH_S2) {
            trace.scenEnd("S2");
            phase = PH_S4;
            s4Step = 1;
            trace.scenBegin("S4.1");
            applyS4Step(1);
            scheduleStartB();
        } else if (phase == PH_S4) {
            trace.scenEnd("S4." + s4Step);
            s4Step++;
            if (s4Step <= S4_STEPS) {
                trace.scenBegin("S4." + s4Step);
                applyS4Step(s4Step);
                scheduleStartB();
            } else {
                phase = PH_D1;
                trace.scenBegin("S3D1");
                scheduleStartB();
            }
        } else if (phase == PH_D1) {
            trace.scenEnd("S3D1");
            phase = PH_LCX;
            lcxStep = 1;
            beginLcxStep();
        } else if (phase == PH_LCX) {
            trace.scenEnd(lcxScenario(lcxStep));
            teardownLcxStep(lcxStep);
            lcxStep++;
            if (lcxStep <= LCX_STEPS) {
                beginLcxStep();
            } else {
                phase = PH_FT;
                runFragments();
            }
        }
    }

    private void applyS4Step(int step) {
        switch (step) {
            case 2:
                unregisterActivityLifecycleCallbacks(obsB);
                trace.event("ProbeApp", "unregisterCallback", "-", "-", "-");
                break;
            case 3:
                registerActivityLifecycleCallbacks(obsC);
                registerActivityLifecycleCallbacks(obsC);
                trace.event("ProbeApp", "registerCallback", "-", "-", "-");
                break;
            case 4:
                unregisterActivityLifecycleCallbacks(obsC);
                trace.event("ProbeApp", "unregisterCallback", "-", "-", "-");
                break;
            case 5:
                unregisterActivityLifecycleCallbacks(obsC);
                trace.event("ProbeApp", "unregisterCallback", "-", "-", "-");
                break;
            default:
                break;
        }
    }

    private static String lcxScenario(int step) {
        switch (step) {
            case 1: return "L-SNAP";
            case 2: return "L-REGD";
            case 3: return "L-EQUALS";
            default: return "L-?";
        }
    }

    private void beginLcxStep() {
        trace.scenBegin(lcxScenario(lcxStep));
        switch (lcxStep) {
            case 1:
                obsMut = new Observer("AppObs-MUT");
                obsVict = new Observer("AppObs-VICT");
                registerActivityLifecycleCallbacks(obsMut);
                trace.event("ProbeApp", "registerCallback", "-", "-", "-");
                registerActivityLifecycleCallbacks(obsVict);
                trace.event("ProbeApp", "registerCallback", "-", "-", "-");
                obsMut.actionEvent = "onActivityCreated";
                obsMut.action = new Runnable() {
                    @Override
                    public void run() {
                        unregisterActivityLifecycleCallbacks(obsVict);
                    }
                };
                obsMut.actionName = "unregisterCallback";
                break;
            case 2:
                obsReg = new Observer("AppObs-REG");
                obsNew = new Observer("AppObs-NEW");
                registerActivityLifecycleCallbacks(obsReg);
                trace.event("ProbeApp", "registerCallback", "-", "-", "-");
                obsReg.actionEvent = "onActivityCreated";
                obsReg.action = new Runnable() {
                    @Override
                    public void run() {
                        registerActivityLifecycleCallbacks(obsNew);
                    }
                };
                obsReg.actionName = "registerCallback";
                break;
            case 3:
                obsEq1 = new EqualsObserver("AppObs-EQ1", "eq");
                obsEq2 = new EqualsObserver("AppObs-EQ2", "eq");
                registerActivityLifecycleCallbacks(obsEq1);
                trace.event("ProbeApp", "registerCallback", "-", "-", "-");
                // distinct instance, equal key -> removes obsEq1 (ArrayList.remove)
                unregisterActivityLifecycleCallbacks(obsEq2);
                trace.event("ProbeApp", "unregisterCallback", "-", "-", "-");
                break;
            default:
                break;
        }
        scheduleStartB();
    }

    private void teardownLcxStep(int step) {
        switch (step) {
            case 1:
                unregisterActivityLifecycleCallbacks(obsMut);
                break;
            case 2:
                unregisterActivityLifecycleCallbacks(obsNew);
                unregisterActivityLifecycleCallbacks(obsReg);
                break;
            default:
                break;
        }
    }

    private void runFragments() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity a = currentA;
                    if (a == null) {
                        trace.recordException("driver: no current ActA for FT scenarios");
                    } else {
                        FragmentProbe.run(trace, a);
                    }
                } catch (Throwable e) {
                    trace.recordException("driver: FT scenarios failed: " + e);
                }
                finalizeProbe();
            }
        }, SETTLE_MS);
    }

    private void scheduleStartB() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                ActA a = currentA;
                if (a == null) {
                    trace.recordException("driver: no current ActA to start ActB");
                    return;
                }
                try {
                    a.startB();
                } catch (Throwable e) {
                    trace.recordException("driver: startB failed: " + e);
                }
            }
        }, SETTLE_MS);
    }

    private void scheduleFinishB(final ActB b) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    b.finish();
                } catch (Throwable e) {
                    trace.recordException("driver: finishB failed: " + e);
                }
            }
        }, SETTLE_MS);
    }

    private void finalizeProbe() {
        if (finalized) {
            return;
        }
        finalized = true;
        String json = buildResultsJson();
        writeResults(json);
        trace.line("LIFE_JSON_BEGIN");
        trace.line(json);
        trace.line("LIFE_JSON_END");
        trace.scenBegin("S3D2");
        trace.line("LIFE_ALL_DONE");
        phase = PH_D2;
        // Terminal scenario: runs AFTER the aggregate + completion marker so a
        // framework mCalled crash here cannot destroy the reference evidence.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runTerminalThrow();
            }
        }, SETTLE_MS);
    }

    /**
     * L-THROW terminal scenario. Runs last, after LIFE_JSON_END/LIFE_ALL_DONE.
     * An observer throws from inside Activity.onCreate's dispatch; the throw
     * escapes super.onCreate(), so on Android mCalled stays false and
     * ActivityThread.performLaunchActivity raises SuperNotCalledException
     * (fatal). The trace therefore ends here; the verifier accepts the run as
     * long as the terminal prefix ran and the later observer (AppObs-POST) did
     * not run for the throwing event.
     */
    private void runTerminalThrow() {
        obsThrow = new Observer("AppObs-THROW");
        obsPost = new Observer("AppObs-POST");
        terminalArmed = true;
        trace.scenBegin("L-THROW");
        registerActivityLifecycleCallbacks(obsThrow);
        trace.event("ProbeApp", "registerCallback", "-", "-", "-");
        registerActivityLifecycleCallbacks(obsPost);
        trace.event("ProbeApp", "registerCallback", "-", "-", "-");
        obsThrow.throwOn = "onActivityCreated";
        scheduleStartB();
    }

    /** Called by ActB after it catches the terminal observer throw. */
    void terminalThrowObserved() {
        if (terminalArmed && trace != null) {
            trace.line(TERMINAL_MARK);
        }
    }

    private String buildResultsJson() {
        List<String> evts = trace.events();
        List<String> done = trace.endedScenarios();
        List<String> exc = trace.exceptions();

        StringBuilder b = new StringBuilder(8192);
        b.append("{\"task_id\":\"").append(TASK_ID).append('"');
        b.append(",\"probe\":\"").append(PROBE_ID).append('"');
        b.append(",\"run_id\":\"").append(Trace.esc(runId)).append('"');
        b.append(",\"config\":{");
        b.append("\"package\":\"").append(PKG).append('"');
        b.append(",\"application\":\"org.atl.tests.lifecycle.ProbeApp\"");
        b.append(",\"main_activity\":\"org.atl.tests.lifecycle.ActA\"");
        b.append(",\"secondary_activity\":\"org.atl.tests.lifecycle.ActB\"");
        b.append(",\"observers\":[\"AppObs-A\",\"AppObs-B\",\"AppObs-C\","
                + "\"AppObs-MUT\",\"AppObs-VICT\",\"AppObs-REG\",\"AppObs-NEW\","
                + "\"AppObs-THROW\",\"AppObs-POST\",\"AppObs-EQ1\",\"AppObs-EQ2\"]");
        b.append(",\"scenarios\":[").append(scenarioIdsQuoted()).append(']');
        b.append(",\"externally_driven\":[\"S3D2\"]");
        b.append(",\"intent_extras\":false");
        b.append(",\"launch\":\"launcher\"");
        b.append('}');
        b.append(",\"observations\":{");
        b.append("\"scenarios_completed\":[").append(joinQuoted(done)).append(']');
        b.append(",\"awaiting_external_close\":true");
        b.append(",\"finish_activity_for_d1\":\"ActB (ActA kept resumed for S3D2)\"");
        b.append('}');
        b.append(",\"events\":[").append(join(evts)).append(']');
        b.append(",\"exceptions\":[").append(joinQuoted(exc)).append(']');
        b.append('}');
        return b.toString();
    }

    private static final String[] SCENARIO_IDS = {
        "S1", "S2", "S4.1", "S4.2", "S4.3", "S4.4", "S4.5", "S3D1",
        "L-SNAP", "L-REGD", "L-EQUALS",
        "F-ADD-TAG", "F-TAG-READ", "F-DUP-TAG", "F-NULL-MISS", "F-PRE-COMMIT",
        "F-REMOVE", "S3D2", "L-THROW",
    };

    private static String scenarioIdsQuoted() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SCENARIO_IDS.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(SCENARIO_IDS[i]).append('"');
        }
        return b.toString();
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
            b.append('"').append(Trace.esc(items.get(i))).append('"');
        }
        return b.toString();
    }

    private void writeResults(String json) {
        FileOutputStream out = null;
        Writer w = null;
        try {
            out = new FileOutputStream(new File(getFilesDir(), "results.json"), false);
            w = new OutputStreamWriter(out, "UTF-8");
            w.write(json);
            w.flush();
        } catch (Exception e) {
            trace.recordException("results.json write failed: " + e);
        } finally {
            try {
                if (w != null) {
                    w.close();
                } else if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}