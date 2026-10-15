/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B, LOCAL gate): the control Activity that drives
 * acceptance rows S01-S08, S14a and S14b through the production service path and
 * emits exactly one verdict line per row plus the final `SVC_GATE_DONE <gate>`
 * marker.
 *
 * Gate selection: ATL_SVC_GATE (default `local`). This file builds the local
 * rows below; the remote gates `fake`, `caps`, `fault`, `ladder` and `child`
 * (rows S09-S13, S14c, S15) are built by RemoteScenarios against the process
 * backend. A gate this tree does not know builds no row at all: it only emits
 * `SVC_GATE_DONE <gate>` and ends the session, so run.sh reports its rows
 * MISSING, never PASS. Every gate ends with `SVC_GATE_DONE <gate>`.
 *
 * Shape of the local session:
 *
 *  - Every row is a Scenario: an ordered list of Steps. The pump posts one step
 *    at a time on the main Handler, ~250 ms apart, so the controller's
 *    main-looper-posted onCreate/onBind/onStartCommand/onUnbind/onDestroy work
 *    runs between steps. The pump never blocks the main thread and never calls
 *    Thread.sleep().
 *  - Every step has a 5 s watchdog and every row a 20 s budget; either one
 *    aborts the row with `SVC <id> FAIL timeout <observed state>` instead of
 *    hanging, and the pump continues with the next row. Exactly one verdict
 *    line is emitted per row (Checks).
 *  - The last step of each row is an observation window with no action: it is
 *    what lets "never" and "only after" be asserted ("zero onServiceConnected
 *    ever", "onDestroy only after the last unbind").
 *
 * Production anchors this file asserts against (no production edit):
 *   ContextImpl.java:377                bindService(Intent,int,Executor,ServiceConnection)
 *   ATLServiceController.java:243/:269  isolated / remote placement refusal
 *   ATLServiceController.java:584       stopService() (registry lookup only)
 *   ATLServiceRegistry.java:627         stop(): keptBound while clients remain
 *   ATLServiceRegistry.java:680         unbind(): IllegalArgumentException "Service not registered: "
 */
package org.atl.tests.service;

import static org.atl.tests.service.Gate.*;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class ControlActivity extends Activity {

    private static final long INITIAL_MS = 500L;
    /** Observation window of a "never" row; > 1 s, as S08 requires. */
    private static final long OBSERVE_MS = 1500L;
    private static final long STEP_TIMEOUT_MS = 5000L;

    private static final String ACTION_A = "org.atl.tests.service.SVC.A";
    private static final String ACTION_B = "org.atl.tests.service.SVC.B";
    private static final String EXTRA = "tag";

    /** The exact placement refusals, ATLServiceController.java:243/:269. */
    private static final String REFUSAL_ISOLATED =
        "ATL does not support remote or isolated services: org.atl.tests.service.SvcIsolated";
    private static final String REFUSAL_REMOTE =
        "ATL does not support remote or isolated services: org.atl.tests.service.SvcRemoteNoBackend";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Scenario> scenarios = new ArrayList<Scenario>();

    /** ATL_SVC_GATE of this launch; also the last line's marker. */
    private String gate = "local";

    private int scenarioIndex;
    private int stepIndex;
    private int stepGeneration;
    private boolean rowAborted;
    private boolean done;
    private Runnable rowWatchdog;
    private Runnable stepWatchdog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String requested = System.getenv("ATL_SVC_GATE");
        this.gate = requested == null || requested.length() == 0 ? "local" : requested;
        EventLog.line("SVC_GATE " + gate + " begin");
        // The app's OWN record of this launch, next to the driver's annotation:
        // without it a row log cannot tell WHICH loader process and WHICH backend
        // produced the lines below, and a row served by a spawned child would look
        // exactly like a row served by a local instance.
        EventLog.line("SVC_APP_PID " + gate + " " + android.os.Process.myPid()
            + " (the loader process; a row served by a spawned child must show a different pid)");
        EventLog.line("SVC_APP_BACKEND " + gate
            + " ATL_PROCESS_BACKEND=" + envOrUnset("ATL_PROCESS_BACKEND")
            + " ATL_SERVICE_PROCESS_FAULT=" + envOrUnset("ATL_SERVICE_PROCESS_FAULT")
            + " ATL_SERVICE_PROCESS=" + envOrUnset("ATL_SERVICE_PROCESS")
            + " realHost=" + realHostState());
        if ("local".equals(gate)) {
            buildScenarios();
        }
        // The remote gates (fake/caps/fault/ladder/child/realhost, rows S09-S13,
        // S14c, S15) are built against the process backend here. A gate this tree
        // does not know adds no row, so its launch leaves only the driver's marker
        // and run.sh reports those rows MISSING instead of a PASS nobody earned.
        RemoteScenarios.build(this, scenarios, gate);
        if (scenarios.isEmpty()) {
            EventLog.line("SVC_GATE_DONE " + gate);
            finish();
            return;
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                startRow(0);
            }
        }, INITIAL_MS);
    }

    // ---------------------------------------------------------------------
    // the step pump
    // ---------------------------------------------------------------------

    private void startRow(int index) {
        if (index >= scenarios.size()) {
            finishGate();
            return;
        }
        scenarioIndex = index;
        stepIndex = 0;
        rowAborted = false;
        stepGeneration++;
        final Scenario row = scenarios.get(index);
        EventLog.event(row.id, "begin", "steps=" + row.steps.size());
        rowWatchdog = new Runnable() {
            public void run() {
                abortRow("row budget " + row.budgetMs + "ms exceeded at step " + stepIndex);
            }
        };
        handler.postDelayed(rowWatchdog, row.budgetMs);
        runStep();
    }

    /** Posts the next step's action (or its observation window) and returns. */
    private void runStep() {
        if (rowAborted) {
            return;
        }
        final Scenario row = scenarios.get(scenarioIndex);
        if (stepIndex >= row.steps.size()) {
            verdict(row, null);
            return;
        }
        final Step step = row.steps.get(stepIndex);
        stepIndex++;
        final int generation = ++stepGeneration;
        final int number = stepIndex;
        stepWatchdog = new Runnable() {
            public void run() {
                if (generation != stepGeneration) {
                    return;
                }
                stepGeneration++;
                abortRow("step " + number + " did not complete within " + STEP_TIMEOUT_MS
                    + "ms (main looper is not draining)");
            }
        };
        handler.postDelayed(stepWatchdog, STEP_TIMEOUT_MS);
        handler.postDelayed(new Runnable() {
            public void run() {
                if (generation != stepGeneration || rowAborted) {
                    return;
                }
                handler.removeCallbacks(stepWatchdog);
                if (step.action != null) {
                    // A step action is only the trigger; a production exception it
                    // does not expect is evidence, not something to swallow.
                    try {
                        step.action.run(row.checks);
                    } catch (Throwable t) {
                        row.checks.fail("step " + number + " threw "
                            + t.getClass().getName() + ": " + t.getMessage());
                        EventLog.event(row.id, "stepThrew", "step=" + number + " "
                            + t.getClass().getName() + ": " + t.getMessage());
                    }
                }
                runStep();
            }
        }, step.delay);
    }

    private void abortRow(String why) {
        if (rowAborted) {
            return;
        }
        rowAborted = true;
        stepGeneration++;
        handler.removeCallbacks(rowWatchdog);
        handler.removeCallbacks(stepWatchdog);
        final Scenario row = scenarios.get(scenarioIndex);
        EventLog.event(row.id, "timeout", why);
        // Fail the checks as well, so a row that times out after already-observed
        // divergences reports both.
        row.checks.fail("timeout: " + why);
        verdict(row, why);
    }

    /** The row's single verdict line, then the next row. Main looper only. */
    private void verdict(Scenario row, String timeout) {
        if (row.verdictDone) {
            return;
        }
        row.verdictDone = true;
        rowAborted = true;
        stepGeneration++;
        handler.removeCallbacks(rowWatchdog);
        handler.removeCallbacks(stepWatchdog);
        if (timeout == null) {
            for (Action check : row.verdictChecks) {
                try {
                    check.run(row.checks);
                } catch (Throwable t) {
                    row.checks.fail("verdict check threw " + t.getClass().getName() + ": "
                        + t.getMessage());
                }
            }
        }
        if (timeout != null) {
            EventLog.line("SVC " + row.id + " FAIL timeout " + timeout
                + " observed=" + EventLog.lastFor(row.id));
        } else if (row.checks.passed()) {
            EventLog.line("SVC " + row.id + " PASS");
        } else {
            EventLog.line("SVC " + row.id + " FAIL " + row.checks.report());
        }
        startRow(scenarioIndex + 1);
    }

    private void finishGate() {
        if (done) {
            return;
        }
        done = true;
        EventLog.line("SVC_GATE_DONE " + gate);
        finish();
    }

    private Intent svc(Class<?> cls) {
        return new Intent(this, cls);
    }

    /** The value of an env var, or "unset": the app's own record of what it was launched with. */
    private static String envOrUnset(String name) {
        String value = System.getenv(name);
        return value == null ? "unset" : value;
    }

    /**
     * Whether the candidate build's real process host is loadable as production
     * loads it (ATLServiceController.selectProcessBackend, Class.forName). A
     * reflective failure must never be silent, so anything but a clean load is
     * reported in the same line, which is what the driver greps into a row log.
     */
    private static String realHostState() {
        try {
            Class.forName("android.atl.ATLProcessHost");
            return "present";
        } catch (ClassNotFoundException e) {
            return "absent";
        } catch (NoClassDefFoundError e) {
            return "absent";
        } catch (RuntimeException e) {
            return "absent (probe failed: " + e + ")";
        }
    }

    private void buildScenarios() {
        scenarios.add(scenarioS01());
        scenarios.add(scenarioS02());
        scenarios.add(scenarioS03());
        scenarios.add(scenarioS04());
        scenarios.add(scenarioS05());
        scenarios.add(scenarioS06());
        scenarios.add(scenarioS07());
        scenarios.add(scenarioS08());
        scenarios.add(scenarioS14("S14a", SvcIsolated.class, REFUSAL_ISOLATED));
        scenarios.add(scenarioS14b());
    }

    // ---------------------------------------------------------------------
    // S01 - start ids 1..N; stopSelf(older id) must not stop, stopSelf() must
    // ---------------------------------------------------------------------
    private Scenario scenarioS01() {
        final Scenario row = new Scenario("S01");
        final Intent plain = svc(SvcIds.class);
        // The third start carries the older start id (2) while the record's
        // current id is 3, so stopSelf(2) has to be refused.
        final Intent stopOlder = svc(SvcIds.class).putExtra("cmd", "stopOld").putExtra("oldId", 2);
        final Intent stopCurrent = svc(SvcIds.class).putExtra("cmd", "stopNow");

        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService #1", attempt("S01", "start1", new Attempt() {
                    public Object run() {
                        return startService(new Intent(plain));
                    }
                }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService #2", attempt("S01", "start2", new Attempt() {
                    public Object run() {
                        return startService(new Intent(plain));
                    }
                }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService #3 (stopSelf(2))",
                    attempt("S01", "start3", new Attempt() {
                        public Object run() {
                            return startService(new Intent(stopOlder));
                        }
                    }));
            }
        });
        row.observe();
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService #4 (stopSelf())",
                    attempt("S01", "start4", new Attempt() {
                        public Object run() {
                            return startService(new Intent(stopCurrent));
                        }
                    }));
            }
        });
        row.observe();

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S01", "SvcIds.onCreate", "", 1);
                expectCount(checks, "S01", "SvcIds.onStartCommand", "", 4);
                expectCount(checks, "S01", "SvcIds.onStartCommand", "id=1 ", 1);
                expectCount(checks, "S01", "SvcIds.onStartCommand", "id=2 ", 1);
                expectCount(checks, "S01", "SvcIds.onStartCommand", "id=3 ", 1);
                expectCount(checks, "S01", "SvcIds.onStartCommand", "id=4 flags=0 cmd=stopNow", 1);
                expectBefore(checks, "S01", "SvcIds.onStartCommand", "id=1 ",
                    "SvcIds.onStartCommand", "id=2 ");
                expectBefore(checks, "S01", "SvcIds.onStartCommand", "id=2 ",
                    "SvcIds.onStartCommand", "id=3 ");
                expectCount(checks, "S01", "SvcIds.stopSelf", "startId=2 ", 1);
                expectCount(checks, "S01", "SvcIds.stopSelf", "startId=current", 1);
                // The refused older id must leave the record alive: start #4 still
                // gets the record's next id (4, not a fresh 1) ...
                expectBefore(checks, "S01", "SvcIds.stopSelf", "startId=2 ",
                    "SvcIds.onStartCommand", "id=4 ");
                expectBefore(checks, "S01", "SvcIds.onStartCommand", "id=3 ",
                    "SvcIds.onStartCommand", "id=4 ");
                // ... and only the unconditional stopSelf() destroys it.
                expectCount(checks, "S01", "SvcIds.onDestroy", "", 1);
                expectBefore(checks, "S01", "SvcIds.stopSelf", "startId=current",
                    "SvcIds.onDestroy", "");
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S02 - a stopped service stays alive while a client is bound
    // ---------------------------------------------------------------------
    private Scenario scenarioS02() {
        final Scenario row = new Scenario("S02");
        final Connection lifetimeClient = new Connection("S02", "lifetime");
        final Intent lifetime = svc(SvcLifetime.class);
        final CallOutcome[] stopBound = new CallOutcome[1];

        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService(SvcLifetime)", attempt("S02", "startLifetime",
                    new Attempt() {
                        public Object run() {
                            return startService(new Intent(lifetime));
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bindService(SvcLifetime, BIND_AUTO_CREATE)",
                    attempt("S02", "bindLifetime", new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(lifetime),
                                lifetimeClient, Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                stopBound[0] = attempt("S02", "stopService", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(stopService(new Intent(lifetime)));
                    }
                });
            }
        });
        row.observe();
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(last bound client)",
                    attempt("S02", "unbindLifetime", new Attempt() {
                        public Object run() {
                            unbindService(lifetimeClient);
                            return "void";
                        }
                    }));
            }
        });
        row.observe();

        // (b) the same, but the stop comes from the instance itself: stopSelf()
        // inside onStartCommand() while a client is bound.
        final Connection selfClient = new Connection("S02", "self");
        final Intent selfLifetime = svc(SvcSelfLifetime.class);
        final Intent selfStop = svc(SvcSelfLifetime.class).putExtra("cmd", "selfStop");

        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService(SvcSelfLifetime)", attempt("S02", "startSelf",
                    new Attempt() {
                        public Object run() {
                            return startService(new Intent(selfLifetime));
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bindService(SvcSelfLifetime, BIND_AUTO_CREATE)",
                    attempt("S02", "bindSelf", new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(selfLifetime), selfClient,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService(SvcSelfLifetime, cmd=selfStop)",
                    attempt("S02", "startSelfStop", new Attempt() {
                        public Object run() {
                            return startService(new Intent(selfStop));
                        }
                    }));
            }
        });
        row.observe();
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcSelfLifetime client)",
                    attempt("S02", "unbindSelf", new Attempt() {
                        public Object run() {
                            unbindService(selfClient);
                            return "void";
                        }
                    }));
            }
        });
        row.observe();

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectSequence(checks, "S02", "SvcLifetime.", "SvcLifetime.onCreate",
                    "SvcLifetime.onStartCommand", "SvcLifetime.onBind", "SvcLifetime.onUnbind",
                    "SvcLifetime.onDestroy");
                checks.eq("lifetime client connects", 1, lifetimeClient.connects);
                checks.eq("lifetime client null bindings", 0, lifetimeClient.nulls);
                checks.eq("lifetime client disconnects", 0, lifetimeClient.disconnects);
                expectReturned(checks, "stopService() while a client is bound", stopBound[0], "true");
                expectBefore(checks, "S02", "lifetime.onServiceConnected", "",
                    "SvcLifetime.onUnbind", "");
                expectBefore(checks, "S02", "call.stopService", "", "SvcLifetime.onDestroy", "");
                expectBefore(checks, "S02", "SvcLifetime.onUnbind", "", "SvcLifetime.onDestroy", "");

                // The stopped-while-bound instance keeps running, so the sequence is
                // the second onStartCommand, its own stopSelf event (refused), the
                // unbind and only then the destroy.
                expectSequence(checks, "S02", "SvcSelfLifetime.", "SvcSelfLifetime.onCreate",
                    "SvcSelfLifetime.onStartCommand", "SvcSelfLifetime.onBind",
                    "SvcSelfLifetime.onStartCommand", "SvcSelfLifetime.stopSelf",
                    "SvcSelfLifetime.onUnbind", "SvcSelfLifetime.onDestroy");
                checks.eq("self-stop client connects", 1, selfClient.connects);
                expectCount(checks, "S02", "SvcSelfLifetime.stopSelf", "startId=current", 1);
                expectBefore(checks, "S02", "SvcSelfLifetime.stopSelf", "startId=current",
                    "SvcSelfLifetime.onUnbind", "");
                expectBefore(checks, "S02", "SvcSelfLifetime.stopSelf", "startId=current",
                    "SvcSelfLifetime.onDestroy", "");
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S03 - binding identity is Intent.filterEquals: extras are not an identity,
    // a different action is
    // ---------------------------------------------------------------------
    private Scenario scenarioS03() {
        final Scenario row = new Scenario("S03");
        final Connection client = new Connection("S03", "identity");
        final Intent first = svc(SvcIdentity.class).setAction(ACTION_A).putExtra(EXTRA, "one");
        final Intent sameIdentity = svc(SvcIdentity.class).setAction(ACTION_A).putExtra(EXTRA, "two");
        final Intent otherIdentity = svc(SvcIdentity.class).setAction(ACTION_B);

        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(action A, extra one)", attempt("S03", "bindA1",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(first), client,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(action A, extra two)", attempt("S03", "bindA2",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(sameIdentity), client,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(action B)", attempt("S03", "bindB",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(otherIdentity), client,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.observe();

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S03", "SvcIdentity.onCreate", "", 1);
                expectCount(checks, "S03", "SvcIdentity.onBind", "", 2);
                // extras do not create an identity: action A is onBind()-ed once
                expectCount(checks, "S03", "SvcIdentity.onBind", "action=" + ACTION_A, 1);
                expectCount(checks, "S03", "SvcIdentity.onBind", "action=" + ACTION_B, 1);
                expectBefore(checks, "S03", "SvcIdentity.onBind", "action=" + ACTION_A,
                    "SvcIdentity.onBind", "action=" + ACTION_B);
                // LoadedApk.java:2414 doConnected() early-returns `old != null &&
                // old.binder == service`: one connection keeps one ConnectionInfo per
                // ComponentName, so a second identity on the same component with the
                // same binder object delivers no second callback. onBind() still runs
                // once per identity (2), the client connects once (1).
                checks.eq("identity client connects (two identities, one binder object)",
                    1, client.connects);
                checks.ok("identity client got one binder", client.lastBinder != null);
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S04 - onBind() == null -> onNullBinding; onUnbind() == true -> onRebind
    // ---------------------------------------------------------------------
    private Scenario scenarioS04() {
        final Scenario row = new Scenario("S04");
        final Connection nullClient = new Connection("S04", "null");
        final Intent nullSvc = svc(SvcNullBind.class);
        final Intent rebindSvc = svc(SvcRebindStarted.class);
        final CallOutcome[] stopRebind = new CallOutcome[1];
        final Intent cycleSvc = svc(SvcCycles.class);

        // (a) onBind() == null is reported as onNullBinding(), never as a connect.
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcNullBind)", attempt("S04", "bindNull",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(nullSvc), nullClient,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcNullBind client)",
                    attempt("S04", "unbindNull", new Attempt() {
                        public Object run() {
                            unbindService(nullClient);
                            return "void";
                        }
                    }));
            }
        });

        // (b) SVC-017 started-service parity: onUnbind() == true on a *started*
        //     instance keeps the instance, so the next bind of the same identity
        //     does not re-run onBind() and reports onRebind() + a connect to the
        //     SAME binder object. The instance only dies with stopService().
        final Connection rebindClient = new Connection("S04", "rebind");
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService(SvcRebindStarted)",
                    attempt("S04", "startRebind", new Attempt() {
                        public Object run() {
                            return startService(new Intent(rebindSvc));
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcRebindStarted) #1", attempt("S04", "bindRebind1",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(rebindSvc), rebindClient,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcRebindStarted client) #1",
                    attempt("S04", "unbindRebind1", new Attempt() {
                        public Object run() {
                            unbindService(rebindClient);
                            return "void";
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcRebindStarted) #2 (same identity)",
                    attempt("S04", "bindRebind2", new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(rebindSvc), rebindClient,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcRebindStarted client) #2",
                    attempt("S04", "unbindRebind2", new Attempt() {
                        public Object run() {
                            unbindService(rebindClient);
                            return "void";
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                stopRebind[0] = attempt("S04", "stopRebind", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(stopService(new Intent(rebindSvc)));
                    }
                });
            }
        });
        row.observe();

        // (c) SVC-008 purely-bound cycles: with no start, the last unbind() of a
        //     client whose onUnbind() returned true still destroys the instance
        //     (ATL destroys a purely bound service on the last unbind, as the
        //     reference does), so the next bind is a NEW instance: new onCreate(),
        //     new onBind(), a different binder, and no onRebind().
        final Connection cycleClient = new Connection("S04", "cycle");
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcCycles) #1", attempt("S04", "bindCycle1",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(cycleSvc), cycleClient,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcCycles client) #1",
                    attempt("S04", "unbindCycle1", new Attempt() {
                        public Object run() {
                            unbindService(cycleClient);
                            return "void";
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcCycles) #2 (new instance expected)",
                    attempt("S04", "bindCycle2", new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(cycleSvc), cycleClient,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcCycles client) #2",
                    attempt("S04", "unbindCycle2", new Attempt() {
                        public Object run() {
                            unbindService(cycleClient);
                            return "void";
                        }
                    }));
            }
        });
        row.observe();

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                // (a) a null binder is reported as onNullBinding, never as a connect
                checks.eq("null client null-bindings", 1, nullClient.nulls);
                checks.eq("null client connects", 0, nullClient.connects);
                checks.eq("null client disconnects", 0, nullClient.disconnects);
                expectCount(checks, "S04", "null.onNullBinding", "comp=SvcNullBind", 1);
                expectCount(checks, "S04", "SvcNullBind.onBind", "action=none", 1);
                expectSubsequence(checks, "S04", "SvcNullBind.", "SvcNullBind.onCreate",
                    "SvcNullBind.onBind", "SvcNullBind.onUnbind", "SvcNullBind.onDestroy");

                // (b) SVC-017: the kept instance is the same instance
                expectCount(checks, "S04", "SvcRebindStarted.onCreate", "", 1);
                expectCount(checks, "S04", "SvcRebindStarted.onStartCommand", "", 1);
                expectCount(checks, "S04", "SvcRebindStarted.onBind", "", 1);
                expectCountContaining(checks, "S04", "SvcRebindStarted.onUnbind", "ret=true", 2);
                expectCount(checks, "S04", "SvcRebindStarted.onRebind", "", 1);
                expectCount(checks, "S04", "SvcRebindStarted.onDestroy", "", 1);
                expectSubsequence(checks, "S04", "SvcRebindStarted.", "SvcRebindStarted.onCreate",
                    "SvcRebindStarted.onStartCommand", "SvcRebindStarted.onBind",
                    "SvcRebindStarted.onUnbind", "SvcRebindStarted.onRebind",
                    "SvcRebindStarted.onUnbind", "SvcRebindStarted.onDestroy");
                expectBeforeContaining(checks, "S04", "SvcRebindStarted.onUnbind", "ret=true",
                    "SvcRebindStarted.onRebind", "");
                expectBefore(checks, "S04", "SvcRebindStarted.onRebind", "",
                    "SvcRebindStarted.onDestroy", "");
                expectBefore(checks, "S04", "call.stopRebind", "", "SvcRebindStarted.onDestroy", "");
                checks.eq("started-parity client connects (bind, rebind)",
                    2, rebindClient.connects);
                checks.eq("started-parity client disconnects", 0, rebindClient.disconnects);
                checks.ok("started-parity rebind reconnects to the same binder object",
                    rebindClient.binders.size() == 2
                        && rebindClient.binders.get(0) == rebindClient.binders.get(1));
                expectReturned(checks, "stopService(SvcRebindStarted) after the rebind",
                    stopRebind[0], "true");

                // (c) SVC-008: a purely bound instance does not survive its last
                //     unbind, even with onUnbind() == true
                expectCount(checks, "S04", "SvcCycles.onCreate", "", 2);
                expectCount(checks, "S04", "SvcCycles.onBind", "", 2);
                expectCountContaining(checks, "S04", "SvcCycles.onUnbind", "ret=true", 2);
                expectCount(checks, "S04", "SvcCycles.onRebind", "", 0);
                expectCount(checks, "S04", "SvcCycles.onDestroy", "", 2);
                // The first instance is destroyed before the second one is created:
                // that is what makes cycle 2 a new instance rather than a rebind.
                expectNthContainingAfter(checks, "S04", "SvcCycles.onDestroy", "",
                    "SvcCycles.onCreate", "", 1);
                expectSubsequence(checks, "S04", "SvcCycles.", "SvcCycles.onCreate",
                    "SvcCycles.onBind", "SvcCycles.onUnbind", "SvcCycles.onDestroy",
                    "SvcCycles.onCreate", "SvcCycles.onBind", "SvcCycles.onUnbind",
                    "SvcCycles.onDestroy");
                checks.eq("cycles client connects (one per instance)", 2, cycleClient.connects);
                checks.eq("cycles client disconnects", 0, cycleClient.disconnects);
                checks.ok("cycle 2 is a new instance: a different binder object",
                    cycleClient.binders.size() == 2
                        && cycleClient.binders.get(0) != cycleClient.binders.get(1));
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S05 - one ServiceConnection bound to two components
    // ---------------------------------------------------------------------
    private Scenario scenarioS05() {
        final Scenario row = new Scenario("S05");
        final Connection multi = new Connection("S05", "multi");
        final Intent multiA = svc(SvcMultiA.class);
        final Intent multiB = svc(SvcMultiB.class);
        final CallOutcome[] lateUnbind = new CallOutcome[1];

        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcMultiA)", attempt("S05", "bindA", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(bindService(new Intent(multiA), multi,
                            Context.BIND_AUTO_CREATE));
                    }
                }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcMultiB)", attempt("S05", "bindB", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(bindService(new Intent(multiB), multi,
                            Context.BIND_AUTO_CREATE));
                    }
                }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "one unbindService() of the shared connection",
                    attempt("S05", "unbindBoth", new Attempt() {
                        public Object run() {
                            unbindService(multi);
                            return "void";
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                // The connection was already removed from both records above.
                lateUnbind[0] = attempt("S05", "unbindAgain", new Attempt() {
                    public Object run() {
                        unbindService(multi);
                        return "void";
                    }
                });
            }
        });
        row.observe();

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCountContaining(checks, "S05", "multi.onServiceConnected", "comp=SvcMultiA", 1);
                expectCountContaining(checks, "S05", "multi.onServiceConnected", "comp=SvcMultiB", 1);
                checks.eq("multi connects", 2, multi.connects);
                expectBeforeContaining(checks, "S05", "multi.onServiceConnected", "comp=SvcMultiA",
                    "multi.onServiceConnected", "comp=SvcMultiB");
                // one unbindService(connection) removed both registrations
                expectSubsequence(checks, "S05", "SvcMultiA.", "SvcMultiA.onCreate",
                    "SvcMultiA.onBind", "SvcMultiA.onUnbind", "SvcMultiA.onDestroy");
                expectSubsequence(checks, "S05", "SvcMultiB.", "SvcMultiB.onCreate",
                    "SvcMultiB.onBind", "SvcMultiB.onUnbind", "SvcMultiB.onDestroy");
                expectThrew(checks, "second unbindService() of the same connection",
                    lateUnbind[0], "java.lang.IllegalArgumentException", "Service not registered: ");
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S06 - (a) the executor overload, (b) a binding a Service issued from its
    // own context dies with the instance
    // ---------------------------------------------------------------------
    private Scenario scenarioS06() {
        final Scenario row = new Scenario("S06");
        final Connection execClient = new Connection("S06", "exec");
        final Intent execSvc = svc(SvcExec.class);
        final Executor executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "svc-exec-1");
                thread.setDaemon(true);
                return thread;
            }
        });
        final Intent leaky = svc(SvcLeaky.class);
        final CallOutcome[] stopLeaky = new CallOutcome[1];
        final CallOutcome[] leakLateUnbind = new CallOutcome[1];

        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bindService(Intent,int,Executor,ServiceConnection)",
                    attempt("S06", "bindExecExecutor", new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(execSvc),
                                Context.BIND_AUTO_CREATE, executor, execClient));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(executor client)",
                    attempt("S06", "unbindExec", new Attempt() {
                        public Object run() {
                            unbindService(execClient);
                            return "void";
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService(SvcLeaky)", attempt("S06", "startLeaky",
                    new Attempt() {
                        public Object run() {
                            return startService(new Intent(leaky));
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                stopLeaky[0] = attempt("S06", "stopLeaky", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(stopService(new Intent(leaky)));
                    }
                });
            }
        });
        row.observe();
        row.step(new Action() {
            public void run(Checks checks) {
                leakLateUnbind[0] = attempt("S06", "unbindLeaked", new Attempt() {
                    public Object run() {
                        unbindService(SvcLeaky.LEAKY_CONNECTION);
                        return "void";
                    }
                });
            }
        });
        row.observe(OBSERVE_MS);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                // (a) the executor overload dispatched the connect through the executor
                expectCount(checks, "S06", "exec.onServiceConnected", "thread=svc-exec-1 ", 1);
                expectCount(checks, "S06", "exec.onServiceConnected", "", 1);
                checks.eq("executor client connects", 1, execClient.connects);
                checks.eq("executor client null bindings", 0, execClient.nulls);
                expectCount(checks, "S06", "SvcExec.onBind", "", 1);
                checks.ok("executor form does not dispatch on the main looper",
                    EventLog.indexOfWhere("S06", "exec.onServiceConnected", "thread=main ") < 0);

                // (b) the instance-bound registration followed the instance
                expectCountContaining(checks, "S06", "SvcLeaky.bindOwnContext", "ret=true", 1);
                expectCountContaining(checks, "S06", "leak.onServiceConnected", "comp=SvcLeakTarget", 1);
                expectBefore(checks, "S06", "SvcLeaky.bindOwnContext", "",
                    "SvcLeakTarget.onCreate", "");
                expectSubsequence(checks, "S06", "SvcLeakTarget.", "SvcLeakTarget.onCreate",
                    "SvcLeakTarget.onBind", "SvcLeakTarget.onUnbind", "SvcLeakTarget.onDestroy");
                // SVC-004 parity: this instance was only started, never bound, so
                // production must not deliver onUnbind() - there is no binding to
                // release - and the started-only sequence is onCreate/onDestroy.
                expectCount(checks, "S06", "SvcLeaky.onUnbind", "", 0);
                expectSubsequence(checks, "S06", "SvcLeaky.", "SvcLeaky.onCreate",
                    "SvcLeaky.onDestroy");
                expectReturned(checks, "stopService(SvcLeaky)", stopLeaky[0], "true");
                expectThrew(checks, "late unbindService(leaked connection)", leakLateUnbind[0],
                    "java.lang.IllegalArgumentException", "Service not registered: ");
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S07 - bindService()/unbindService() issued from inside onServiceConnected()
    // ---------------------------------------------------------------------
    private Scenario scenarioS07() {
        final Scenario row = new Scenario("S07");
        final Connection first = new Connection("S07", "first");
        final Connection second = new Connection("S07", "second");
        final Intent firstSvc = svc(SvcReenterFirst.class);
        final Intent secondSvc = svc(SvcReenterSecond.class);
        final CallOutcome[] reentrantBind = new CallOutcome[1];

        first.hook = new Connection.Hook() {
            public void onConnected(Connection self, ComponentName name, IBinder binder) {
                reentrantBind[0] = attempt("S07", "reentrantBind", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(bindService(new Intent(secondSvc), second,
                            Context.BIND_AUTO_CREATE));
                    }
                });
            }
        };
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcReenterFirst)", attempt("S07", "bindFirst",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(firstSvc), first,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(SvcReenterFirst client)",
                    attempt("S07", "unbindFirst", new Attempt() {
                        public Object run() {
                            unbindService(first);
                            return "void";
                        }
                    }));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService(service bound from the connect callback)",
                    attempt("S07", "unbindSecond", new Attempt() {
                        public Object run() {
                            unbindService(second);
                            return "void";
                        }
                    }));
            }
        });
        row.observe();

        final Connection third = new Connection("S07", "third");
        final Intent thirdSvc = svc(SvcReenterThird.class);
        final CallOutcome[] reentrantUnbind = new CallOutcome[1];
        final CallOutcome[] lateThirdUnbind = new CallOutcome[1];

        third.hook = new Connection.Hook() {
            public void onConnected(Connection self, ComponentName name, IBinder binder) {
                reentrantUnbind[0] = attempt("S07", "reentrantUnbind", new Attempt() {
                    public Object run() {
                        unbindService(self);
                        return "void";
                    }
                });
            }
        };
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bind(SvcReenterThird)", attempt("S07", "bindThird",
                    new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(bindService(new Intent(thirdSvc), third,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                lateThirdUnbind[0] = attempt("S07", "unbindThird", new Attempt() {
                    public Object run() {
                        unbindService(third);
                        return "void";
                    }
                });
            }
        });
        row.observe(OBSERVE_MS);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                // (1) a bind issued inside onServiceConnected() completed
                checks.ok("the reentrant bindService() ran inside onServiceConnected()",
                    reentrantBind[0] != null);
                expectReturned(checks, "bindService() from inside onServiceConnected()",
                    reentrantBind[0], "true");
                checks.eq("nested client connects", 1, second.connects);
                expectCountContaining(checks, "S07", "second.onServiceConnected",
                    "comp=SvcReenterSecond", 1);
                expectSubsequence(checks, "S07", "SvcReenterSecond.", "SvcReenterSecond.onCreate",
                    "SvcReenterSecond.onBind", "SvcReenterSecond.onUnbind",
                    "SvcReenterSecond.onDestroy");
                expectBefore(checks, "S07", "first.onServiceConnected", "",
                    "call.reentrantBind", "");
                expectBefore(checks, "S07", "call.reentrantBind", "",
                    "second.onServiceConnected", "");
                checks.eq("outer client connects", 1, first.connects);

                // (2) an unbind issued inside onServiceConnected() did not deadlock and
                //     removed the registration: no second connect, and the later
                //     unbindService() of the same connection is unknown.
                checks.ok("the reentrant unbindService() ran inside onServiceConnected()",
                    reentrantUnbind[0] != null);
                expectNoThrow(checks, "unbindService() from inside onServiceConnected()",
                    reentrantUnbind[0]);
                checks.eq("third client connects (only the in-flight one)", 1, third.connects);
                expectCount(checks, "S07", "third.onServiceConnected", "", 1);
                expectCount(checks, "S07", "SvcReenterThird.onBind", "", 1);
                expectBefore(checks, "S07", "third.onServiceConnected", "", "call.reentrantUnbind", "");
                expectThrew(checks, "later unbindService() of the connection the callback unbound",
                    lateThirdUnbind[0], "java.lang.IllegalArgumentException",
                    "Service not registered: ");
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S08 - bind and unbind in the same tick: the client is never connected,
    // the service-side lifecycle still ran
    // ---------------------------------------------------------------------
    private Scenario scenarioS08() {
        final Scenario row = new Scenario("S08");
        final Connection race = new Connection("S08", "race");
        final Intent raceSvc = svc(SvcUnbindRace.class);
        final CallOutcome[] sameTickBind = new CallOutcome[1];
        final CallOutcome[] sameTickUnbind = new CallOutcome[1];

        row.step(new Action() {
            public void run(Checks checks) {
                sameTickBind[0] = attempt("S08", "bindSameTick", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(bindService(new Intent(raceSvc), race,
                            Context.BIND_AUTO_CREATE));
                    }
                });
                sameTickUnbind[0] = attempt("S08", "unbindSameTick", new Attempt() {
                    public Object run() {
                        unbindService(race);
                        return "void";
                    }
                });
            }
        });
        row.observe(OBSERVE_MS);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                EventLog.event("S08", "observedWindow",
                    "windowMs=" + OBSERVE_MS + " connects=" + race.connects);
                expectReturned(checks, "bindService() of the same tick", sameTickBind[0], "true");
                expectNoThrow(checks, "unbindService() of the same tick", sameTickUnbind[0]);
                checks.eq("race client connects after " + OBSERVE_MS + "ms (must never happen)",
                    0, race.connects);
                checks.ok("race client got no callback at all", race.nulls == 0 && race.dieds == 0);
                expectSubsequence(checks, "S08", "SvcUnbindRace.", "SvcUnbindRace.onCreate",
                    "SvcUnbindRace.onBind", "SvcUnbindRace.onUnbind", "SvcUnbindRace.onDestroy");
                expectCount(checks, "S08", "SvcUnbindRace.onBind", "", 1);
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S14a - isolatedProcess must refuse placement explicitly, with the exact
    // message, before anything runs
    // ---------------------------------------------------------------------
    private Scenario scenarioS14(String id, final Class<?> cls, final String refusal) {
        final Scenario row = new Scenario(id);
        final Intent refused = svc(cls);
        final CallOutcome[] start = new CallOutcome[1];
        final CallOutcome[] bind = new CallOutcome[1];
        final CallOutcome[] stop = new CallOutcome[1];

        row.step(new Action() {
            public void run(Checks checks) {
                start[0] = attempt(id, "startService", new Attempt() {
                    public Object run() {
                        return startService(new Intent(refused));
                    }
                });
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                bind[0] = attempt(id, "bindService", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(bindService(new Intent(refused),
                            new Connection(id, "refused"), Context.BIND_AUTO_CREATE));
                    }
                });
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                stop[0] = attempt(id, "stopService", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(stopService(new Intent(refused)));
                    }
                });
            }
        });
        row.observe();

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                checks.eq("expected refusal message names the fully-expanded class",
                    refusal, "ATL does not support remote or isolated services: " + cls.getName());
                expectUnsupported(checks, "startService()", start[0], refusal);
                expectUnsupported(checks, "bindService()", bind[0], refusal);
                expectStopRefusal(checks, "stopService()", stop[0], refusal);
                expectNeverRan(checks, id, cls.getSimpleName());
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S14b - a `:remote` placement: served by a real spawned child, or refused
    // with the frozen message - never a silent local instance in the loader
    // ---------------------------------------------------------------------
    private Scenario scenarioS14b() {
        final Scenario row = new Scenario("S14b").budget(40000);
        final Class<?> cls = SvcRemoteNoBackend.class;
        final String process = getPackageName() + ":remote";
        final Intent intent = svc(cls);
        final Connection client = new Connection("S14b", "remote");
        final CallOutcome[] start = new CallOutcome[1];
        final CallOutcome[] bind = new CallOutcome[1];
        final CallOutcome[] stop = new CallOutcome[1];
        final boolean[] hostUp = new boolean[1];
        final String[] hostWhy = new String[1];
        final int[] childPid = new int[1];

        // No assertion here: which of the two arms these three calls land in is
        // decided by the verdict, once the hosting side has been observed.
        row.step(new Action() {
            public void run(Checks checks) {
                start[0] = attempt("S14b", "startRemote", new Attempt() {
                    public Object run() {
                        return startService(new Intent(intent));
                    }
                });
            }
        });
        row.observe(2500);
        row.step(new Action() {
            public void run(Checks checks) {
                bind[0] = attempt("S14b", "bindRemote", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(bindService(new Intent(intent), client,
                            Context.BIND_AUTO_CREATE));
                    }
                });
            }
        });
        row.observe(2500);
        row.step(new Action() {
            public void run(Checks checks) {
                // The real host's own view, taken through the shared reflective
                // resolver instead of a second one, and only here: by now a spawned
                // child that serves this process must exist and may have appended
                // its own trace lines.
                RemoteScenarios.Liveness live = RemoteScenarios.realHostLiveness(process,
                    "S14b needs the real process host (android.atl.ATLProcessHost) to see whether the"
                    + " :remote process is up");
                hostUp[0] = live.up;
                hostWhy[0] = live.usable ? null : live.why;
                childPid[0] = EventLog.foreignPid("S14b", "SvcRemoteNoBackend.");
                EventLog.event("S14b", "observedHost", "process=" + process + " up=" + hostUp[0]
                    + " childPid=" + childPid[0] + (live.usable ? "" : " host=" + live.why));
            }
        });
        row.step(new Action() {
            public void run(Checks checks) {
                stop[0] = attempt("S14b", "stopRemote", new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(stopService(new Intent(intent)));
                    }
                });
            }
        });
        row.observe(1500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                int local = EventLog.countLocal("S14b", "SvcRemoteNoBackend.");
                // The merged view: a child's callbacks count here, the loader's do not.
                int ran = EventLog.count("S14b", "SvcRemoteNoBackend.onCreate");
                checks.eq("the loader must never run the component (no silent local instance)",
                    0, local);
                boolean refused = isUnsupported(start[0], REFUSAL_REMOTE)
                    && isUnsupported(bind[0], REFUSAL_REMOTE)
                    && isUnsupported(stop[0], REFUSAL_REMOTE);
                boolean started = start[0].thrown == null
                    && String.valueOf(start[0].returned).contains("SvcRemoteNoBackend");
                boolean bound = "true".equals(bind[0].returned);
                boolean connected = client.connects >= 1 && client.lastBinder != null;
                boolean childRan = childPid[0] > 0 && childPid[0] != EventLog.pid() && ran >= 1;
                if (refused) {
                    checks.ok("arm (b): a :remote placement with no hosting backend is refused"
                        + " with the frozen message", true);
                    checks.eq("arm (b): the refused client is never called", 0, client.connects);
                    checks.eq("arm (b): no process ran the component", 0, ran);
                } else if (started && bound && connected && childRan && hostUp[0]) {
                    checks.ok("arm (a): the :remote placement is served by a spawned child (pid "
                        + childPid[0] + " != loader " + EventLog.pid()
                        + "), never by a silent local instance", true);
                } else {
                    checks.fail("S14b: neither arm held: start=" + start[0].describe()
                        + " bind=" + bind[0].describe() + " stop=" + stop[0].describe()
                        + " connects=" + client.connects + " ran=" + ran + " localEvents=" + local
                        + " childPid=" + childPid[0] + " hostUp=" + hostUp[0]
                        + (hostWhy[0] == null ? "" : " host=" + hostWhy[0])
                        + " (a local instance serving a remote request is the failure mode)");
                }
            }
        });
        return row;
    }

    /**
     * expectUnsupported's exact rule as a boolean, for the S14b verdict that has
     * to branch on which arm held before it can assert anything.
     */
    private static boolean isUnsupported(CallOutcome outcome, String message) {
        return outcome.thrown instanceof UnsupportedOperationException
            && message.equals(outcome.thrown.getMessage());
    }

}
