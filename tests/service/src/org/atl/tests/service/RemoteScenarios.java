/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), REMOTE gates: rows S09-S13, S14c and S15 driven
 * through the process backend instead of in the loader's own process.
 *
 * Gate to rows (ATL_SVC_GATE, set by tests/service/run.sh per launch):
 *   fake   - S09, S10, S11a, S11c, S11d, S12, S13c, S15   (ATL_PROCESS_BACKEND=fake)
 *   caps   - S13b                                          (fake, crash cap default 16)
 *   ladder - S13a                                          (fake, MIN_CRASH_INTERVAL_MS=1000)
 *   fault  - S11b                                          (fake, ATL_SERVICE_PROCESS_FAULT=spawn)
 *   child  - S14c                                          (ATL_SERVICE_PROCESS: this loader IS a child)
 *   realhost - S09, S09x, S10, S11c, S15 (NO ATL_PROCESS_BACKEND: real spawned children
 *              through android.atl.ATLProcessHost, one child per android:process)
 *
 * Rows S11a, S11b, S11d, S12, S13a, S13b and S13c are NOT run in the realhost
 * launch: their row path needs the fake kill (ATLFakeProcessBackend.killProcess)
 * or the fake fault-arming seam (armProcessFault), which the real host does not
 * expose. run.sh reports them `not-run` for that launch - never PASS, never FAIL.
 * S09, S11c and S15 need no change for it: they only use production calls and
 * event counts, so they run unmodified against real children (S09x is the one row that
 * carries a seam into that launch, and it is scoped to its own process). S10 gets its own
 * body ({@link #s10Real()}), because in that arm the child exits ITSELF instead
 * of being killed through the fake seam.
 *
 * Every row states the backend it ran against in its own log: run.sh writes the
 * `SVC_GATE BACKEND <name> <backend>` annotation into the launch's stdout.log
 * before the app starts, and extracts it with the row's events into the per-row
 * log. With ATL_PROCESS_BACKEND=fake the hosting side is ATLFakeProcessBackend,
 * an in-loader host that drives the production controller through the real
 * ATLProcessBackend contract and fires the real ProcessDeathListener path, so a
 * row's kill is a real death and never a synthesized callback.
 *
 * Kill timings are NOT arbitrary: the controller's restart delay comes from
 * ATLServiceRegistry.planRestart (lines 1072-1087) - the first death of a record
 * restarts after max(restartDurationMs, 2 x the age of the oldest delivered
 * start), a later death with crashCount > 1 after
 * boundCrashRestartDurationMs x (crashCount - 1), and otherwise after the x4
 * so each row kills as early as its assertions allow (short run time =
 * short first delay) and its observation windows are sized for the delay that
 * schedule produces under that gate's env.
 *
 * The realhost arm has no kill at all: its deaths are the child exiting itself
 * (the S10 fixture's cmd=die, a real death for ATLProcessHost.startReaper), so
 * that row's windows cover the very same planRestart delay a kill would produce
 * - the restart policy is production's either way, only the way the process ends
 * differs.
 *
 * What each row asserts and why (frozen anchors):
 *   S09  Service.stopSelf(int)/(), remote: the record's start-id rule
 *        (`startId >= 0 && lastStartId != startId`) - the same contract as S01 -
 *        plus the return value of that route: stopSelfResult(stale id) is false,
 *        stopSelfResult(current id) is true (phase 2).
 *   S09x stopSelfResult() with the control link to the authority gone (scoped
 *        `stopself-broken-link` seam, :s09x only): false, and nothing is stopped -
 *        the record keeps serving, so the loader was never told (realhost launch).
 *   S10  START_STICKY: the onStartCommand() return code is captured, the record
 *        survives the child's death and is redelivered with a fabricated null
 *        intent, with a bound client and after that client left (fake arm: the
 *        kill seam; realhost arm: the child exits itself).
 *   S11a a child-originated stopSelf() drives the authoritative record's teardown.
 *   S11b the three spawn faults: spawn failure (the client learns through
 *        onServiceDisconnected, never a false connect - the approved deviation),
 *        crash-before-ready (a death: the record restarts, one-shot fault), and
 *        stall-before-ready (no answer: the client is never called, the component
 *        never runs).
 *   S11c onBind() == null remotely is reported as onNullBinding(), never a connect.
 *   S11d onUnbind() == true arms onRebind(): the next bind of the same identity
 *        reports onRebind() and the SAME binder object.
 *   S12  the callback set of a process death is onServiceDisconnected only (never
 *        onBindingDied) while the record is under the bound-crash retry cap, and
 *        the death that reaches the cap tears the binding down (disconnect first,
 *        then binding-died) instead of restarting. REDELIVER_INTENT redelivers the
 *        SAME intent.
 *   S13a the restart ladder, measured from each kill: 300 ms, then the x4 branch,
 *        then x4 again (the ladder gate keeps MIN_CRASH_INTERVAL_MS below the kill
 *        spacing, so each death counts as a fresh crash run).
 *   S13b the acknowledged-redelivery cap (MAX_DONE_EXECUTING_COUNT = 6): the
 *        delivery DOES run out, however many times the process is killed.
 *   S13c START_STICKY + bound client: the bound-crash retry cap tears the record
 *        down after the third death, and the sticky restart carries a fabricated
 *        null intent on a fresh start id.
 *   S14c a child process refuses to place a service in another process
 *        (ATLServiceController.placementRemoteProcess, line 267).
 *   S15  trace parity: the same call sequence through the local and the remote
 *        path produces the same callbacks in the same order with the same details.
 */
package org.atl.tests.service;

import static org.atl.tests.service.Gate.*;

import android.atl.ATLFakeProcessBackend;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;

final class RemoteScenarios {

    private final ControlActivity host;

    private RemoteScenarios(ControlActivity host) {
        this.host = host;
    }

    /** Adds this gate's rows to {@code rows}; a gate this tree does not know adds nothing. */
    static void build(ControlActivity host, ArrayList<Scenario> rows, String gate) {
        RemoteScenarios r = new RemoteScenarios(host);
        if ("fake".equals(gate)) {
            rows.add(r.s09(FAKE));
            rows.add(r.s10());
            rows.add(r.s11a());
            rows.add(r.s11c());
            rows.add(r.s11d());
            rows.add(r.s12());
            rows.add(r.s13c());
            rows.add(r.s15());
        } else if ("caps".equals(gate)) {
            rows.add(r.s13b());
        } else if ("ladder".equals(gate)) {
            rows.add(r.s13a());
        } else if ("fault".equals(gate)) {
            rows.add(r.s11b());
        } else if ("child".equals(gate)) {
            rows.add(r.s14c());
        } else if ("realhost".equals(gate)) {
            // No fake, no fault beyond the scoped S09x seam, no ATL_SERVICE_PROCESS: the
            // remote fixtures resolve the REAL android.atl.ATLProcessHost, so every row's
            // component runs in a really spawned child and S09/S11c/S15 need no change
            // for that.
            rows.add(r.s09(REAL));
            rows.add(r.s09x());
            rows.add(r.s10Real());
            rows.add(r.s11c());
            rows.add(r.s15());
        }
    }

    // ---------------------------------------------------------------------
    // step helpers - the production calls, each recorded as a row TRACE line
    // ---------------------------------------------------------------------

    private Intent svc(Class<?> cls) {
        return new Intent(host, cls);
    }

    /** The expanded process name of a remote fixture: getPackageName() + its PROCESS tag. */
    private String processOf(Class<?> cls) {
        try {
            return host.getPackageName() + cls.getField("PROCESS").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(cls.getName() + " declares no PROCESS constant", e);
        }
    }

    /** The in-loader hosting fake; null when this launch resolves the real process host. */
    private static ATLFakeProcessBackend backend() {
        return ATLFakeProcessBackend.instance();
    }

    /**
     * The hosting side one row body runs against, so the same body can be used by
     * the fake arm and by the real-host arm without either of them silently
     * standing in for the other.
     */
    private interface HostView {
        Liveness liveness(String process, String unusableWhy);
    }

    /**
     * What a {@link HostView} can say about a process. {@code usable} is false when
     * the hosting side this row needs is not in this build: the row then FAILS with
     * {@code why} instead of asserting against a process nobody can see.
     */
    static final class Liveness {
        final boolean usable;
        final boolean up;
        final String why;

        Liveness(boolean usable, boolean up, String why) {
            this.usable = usable;
            this.up = up;
            this.why = why;
        }
    }

    /** The in-loader fake (ATL_PROCESS_BACKEND=fake); a launch without it cannot use this view. */
    private static final class FakeHostView implements HostView {
        public Liveness liveness(String process, String unusableWhy) {
            ATLFakeProcessBackend fake = backend();
            if (fake == null) {
                return new Liveness(false, true, unusableWhy);
            }
            return new Liveness(true, fake.isProcessUp(process), null);
        }
    }

    /**
     * The real process host of the candidate build, resolved exactly as production
     * resolves it (ATLServiceController.selectProcessBackend:
     * `Class.forName("android.atl.ATLProcessHost").getMethod("get")`). A build
     * without the host class makes this view unusable and the row FAILS with that
     * reason: it never falls back to the fake, and never to a local instance. Any
     * other reflective failure is reported just as loudly.
     */
    private static final class RealHostView implements HostView {
        /** The host class' get() and isProcessUp(String), cached once. */
        private final java.lang.reflect.Method get;
        private final java.lang.reflect.Method isProcessUp;
        private final String unusableWhy;

        RealHostView() {
            java.lang.reflect.Method getter = null;
            java.lang.reflect.Method up = null;
            String why = null;
            try {
                Class<?> cls = Class.forName("android.atl.ATLProcessHost");
                getter = cls.getMethod("get");
                up = cls.getMethod("isProcessUp", String.class);
            } catch (ClassNotFoundException e) {
                why = "the real process host (android.atl.ATLProcessHost) is not in this build";
            } catch (NoClassDefFoundError e) {
                why = "the real process host (android.atl.ATLProcessHost) is not in this build";
            } catch (ReflectiveOperationException e) {
                getter = null;
                up = null;
                why = "the real process host (android.atl.ATLProcessHost) is not usable: " + e;
            }
            this.get = getter;
            this.isProcessUp = up;
            this.unusableWhy = why;
        }

        public Liveness liveness(String process, String unusableWhy) {
            if (get == null) {
                // The host's own reason wins over the row's: a build without the host
                // is not the row's missing seam, it is the missing host.
                return new Liveness(false, false, this.unusableWhy);
            }
            final Object host;
            try {
                host = get.invoke(null);
            } catch (ReflectiveOperationException e) {
                return new Liveness(false, false,
                    "android.atl.ATLProcessHost.get() failed: " + e);
            }
            if (host == null) {
                return new Liveness(false, false, "android.atl.ATLProcessHost.get() returned null");
            }
            try {
                return new Liveness(true, Boolean.TRUE.equals(isProcessUp.invoke(host, process)), null);
            } catch (ReflectiveOperationException e) {
                return new Liveness(false, false,
                    "android.atl.ATLProcessHost.isProcessUp(" + process + ") failed: " + e);
            }
        }
    }

    private static final HostView FAKE = new FakeHostView();
    private static final HostView REAL = new RealHostView();

    /**
     * The real host's view of {@code process}, for the S14b row that has to name
     * the arm it landed in (ControlActivity.scenarioS14b): one reflective lookup,
     * shared with the realhost gate's rows instead of a second one.
     */
    static Liveness realHostLiveness(String process, String unusableWhy) {
        return REAL.liveness(process, unusableWhy);
    }

    private void start(Scenario row, final String id, final Intent intent, final String label) {
        row.step(new Action() {
            public void run(Checks checks) {
                expectAccepted(checks, "startService " + label, attempt(row.id, id, new Attempt() {
                    public Object run() {
                        return host.startService(new Intent(intent));
                    }
                }));
            }
        });
    }

    private void bindAuto(Scenario row, final String id, final Intent intent, final Connection client,
                          final String label) {
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "bindService (BIND_AUTO_CREATE) " + label,
                    attempt(row.id, id, new Attempt() {
                        public Object run() {
                            return Boolean.valueOf(host.bindService(new Intent(intent), client,
                                Context.BIND_AUTO_CREATE));
                        }
                    }), "true");
            }
        });
    }

    /** A bind whose placement must be refused: the row asserts the exact refusal. */
    private void bindExpectedRefusal(Scenario row, final String id, final Intent intent,
                                     final Connection client, final String label,
                                     final String message) {
        row.step(new Action() {
            public void run(Checks checks) {
                expectUnsupported(checks, label, attempt(row.id, id, new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(host.bindService(new Intent(intent), client,
                            Context.BIND_AUTO_CREATE));
                    }
                }), message);
            }
        });
    }

    private void startExpectedRefusal(Scenario row, final String id, final Intent intent,
                                      final String label, final String message) {
        row.step(new Action() {
            public void run(Checks checks) {
                expectUnsupported(checks, label, attempt(row.id, id, new Attempt() {
                    public Object run() {
                        return host.startService(new Intent(intent));
                    }
                }), message);
            }
        });
    }

    private void unbind(Scenario row, final String id, final Connection client, final String label) {
        row.step(new Action() {
            public void run(Checks checks) {
                expectNoThrow(checks, "unbindService " + label, attempt(row.id, id, new Attempt() {
                    public Object run() {
                        host.unbindService(client);
                        return "void";
                    }
                }));
            }
        });
    }

    private void stop(Scenario row, final String id, final Intent intent, final String label) {
        row.step(new Action() {
            public void run(Checks checks) {
                expectReturned(checks, "stopService " + label, attempt(row.id, id, new Attempt() {
                    public Object run() {
                        return Boolean.valueOf(host.stopService(new Intent(intent)));
                    }
                }), "true");
            }
        });
    }

    /**
     * Kills the hosting process of {@code cls} for real: the fake fires the
     * production ProcessDeathListener path, so the row observes a death, never a
     * synthesized callback. A row that cannot kill says so and FAILS.
     */
    private void kill(Scenario row, final String id, final Class<?> cls, final String why) {
        row.step(new Action() {
            public void run(Checks checks) {
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("kill " + cls.getSimpleName() + ": no fake backend is installed"
                        + " (ATL_PROCESS_BACKEND=fake), so this row cannot kill a real child");
                    return;
                }
                EventLog.event(row.id, "kill", processOf(cls) + " (" + why + ")");
                fake.killProcess(processOf(cls));
            }
        });
    }

    /** Records the client's callback counters as they stand now, and into {@code into}. */
    private void snapshot(Scenario row, final String id, final String label, final Connection client,
                          final int[] into) {
        row.step(new Action() {
            public void run(Checks checks) {
                into[0] = client.connects;
                into[1] = client.disconnects;
                into[2] = client.dieds;
                into[3] = client.nulls;
                EventLog.event(row.id, "counters", label + " " + client.counters());
            }
        });
    }

    /** {@code count} observation windows of {@code ms} each (the pump caps one at 5 s). */
    private void observes(Scenario row, int count, long ms) {
        for (int i = 0; i < count; i++) {
            row.observe(ms);
        }
    }

    /** The `flags` column of every delivery of {@code cls}, in delivery order. */
    private static String flagsSequence(String id, Class<?> cls) {
        ArrayList<String> details =
            EventLog.sequenceDetailed(id, cls.getSimpleName() + ".onStartCommand");
        StringBuilder sb = new StringBuilder();
        for (String detail : details) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(field(detail, "flags="));
        }
        return sb.toString();
    }

    private static String field(String detail, String name) {
        int at = detail.indexOf(name);
        if (at < 0) {
            return "?";
        }
        int from = at + name.length();
        int to = detail.indexOf(' ', from);
        return to < 0 ? detail.substring(from) : detail.substring(from, to);
    }

    /** The n-th (0-based) restart under a row's kills, or -1: what S13a measures against. */
    private static long killAt(String id, int n) {
        return EventLog.uptimeOfNthContaining(id, "kill", "", n);
    }

    private static long connectAt(String id, String clientName, int n) {
        return EventLog.uptimeOfNthContaining(id, clientName + ".onServiceConnected", "", n);
    }

    // ---------------------------------------------------------------------
    // S09 - a remote record's stopSelf(olderId) must be refused, stopSelf() stops, and
    //       stopSelfResult() returns the authority's own decision (phase 2)
    // ---------------------------------------------------------------------
    private Scenario s09(final HostView host) {
        final Scenario row = new Scenario("S09").budget(45000);
        final Class<?> cls = SvcStopSelf.class;
        final String process = processOf(cls);
        final Intent plain = svc(cls);
        final Intent stopOlder = svc(cls).putExtra("cmd", "stopOld").putExtra("oldId", 2);
        final Intent stopCurrent = svc(cls).putExtra("cmd", "stopNow");

        // Phase 1 - the start-id rule of stopSelf(int)/stopSelf(), unchanged.
        start(row, "start1", plain, "#1");
        row.observe(800);
        start(row, "start2", plain, "#2");
        row.observe(800);
        start(row, "start3", stopOlder, "#3 (stopSelf(2))");
        row.observe(800);
        start(row, "start4", stopCurrent, "#4 (stopSelf())");
        row.observe(2500);

        // Phase 2 - the return value of stopSelfResult(int). Phase 1 ended with the
        // record torn down, so #5 is a NEW instance whose start ids begin at 1 again.
        final Intent stopResultOlder = svc(cls).putExtra("cmd", "stopResOld").putExtra("oldId", 1);
        final Intent stopResultCurrent = svc(cls).putExtra("cmd", "stopResNow");
        start(row, "start5", plain, "#5 (new instance after the phase-1 teardown)");
        row.observe(800);
        start(row, "start6", stopResultOlder, "#6 (stopSelfResult(1): stale id of the new record)");
        row.observe(800);
        start(row, "start7", stopResultCurrent, "#7 (stopSelfResult(current))");
        row.observe(2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S09", "SvcStopSelf.onCreate", "", 2);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "", 7);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "id=1 flags=0 cmd=none", 2);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "id=2 flags=0 cmd=none", 1);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "id=3 flags=0 cmd=stopOld", 1);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "id=4 flags=0 cmd=stopNow", 1);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "id=2 flags=0 cmd=stopResOld", 1);
                expectCount(checks, "S09", "SvcStopSelf.onStartCommand", "id=3 flags=0 cmd=stopResNow", 1);
                expectCount(checks, "S09", "SvcStopSelf.stopSelf", "startId=2 ", 1);
                expectCount(checks, "S09", "SvcStopSelf.stopSelf", "startId=current", 1);
                // The two stopSelfResult arms: the call's own return value is the
                // authority's decision - false for the stale id, true for the current one.
                // A child that decided on its own could not tell these two apart.
                expectCount(checks, "S09", "SvcStopSelf.stopSelfResult",
                    "startId=1 ret=false (older start id", 1);
                expectCount(checks, "S09", "SvcStopSelf.stopSelfResult",
                    "startId=3 ret=true (current start", 1);
                expectCount(checks, "S09", "SvcStopSelf.stopSelfResult", "", 2);
                // The refused older id left the record alive: start #4/#7 got the next
                // id of the SAME record, so the child ran one onCreate() per instance.
                expectBefore(checks, "S09", "SvcStopSelf.stopSelf", "startId=2 ",
                    "SvcStopSelf.onStartCommand", "id=4 ");
                expectBefore(checks, "S09", "SvcStopSelf.stopSelf", "startId=current",
                    "SvcStopSelf.onDestroy", "");
                expectBeforeContaining(checks, "S09", "SvcStopSelf.stopSelfResult", "ret=false",
                    "SvcStopSelf.onStartCommand", "id=3 flags=0 cmd=stopResNow");
                // Phase 2's accepted stop is followed by phase 2's own teardown: the
                // SECOND onDestroy. The first one belongs to phase 1's stopSelf(), and
                // it precedes this call by design, so it cannot be the anchor.
                expectNthContainingAfter(checks, "S09", "SvcStopSelf.stopSelfResult", "ret=true",
                    "SvcStopSelf.onDestroy", "", 1);
                expectCount(checks, "S09", "SvcStopSelf.onDestroy", "", 2);
                Liveness live = host.liveness(process,
                    "S09 needs the fake backend to observe the process going down");
                if (!live.usable) {
                    checks.fail(live.why);
                } else {
                    checks.ok("the hosting process must be gone after the teardown, observed up="
                        + live.up, !live.up);
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S09x - stopSelfResult() with the control link to the authority GONE must return
    //        false and tear nothing down. Armed only for :s09x by run.sh's realhost
    //        launch (ATL_SERVICE_PROCESS_FAULT=stopself-broken-link:<process>), so the
    //        other rows of that launch keep a working link.
    // ---------------------------------------------------------------------
    private Scenario s09x() {
        final Scenario row = new Scenario("S09x").budget(30000);
        final Class<?> cls = SvcStopSelfGone.class;
        final Intent plain = svc(cls);
        final Intent stopResultCurrent = svc(cls).putExtra("cmd", "stopResNow");

        start(row, "start1", plain, "#1");
        row.observe(800);
        start(row, "start2", stopResultCurrent, "#2 (stopSelfResult(current), no control link)");
        row.observe(1500);
        start(row, "start3", plain, "#3 (same instance: the record was never told)");
        row.observe(800);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S09x", "SvcStopSelfGone.onCreate", "", 1);
                expectCount(checks, "S09x", "SvcStopSelfGone.onStartCommand", "", 3);
                expectCount(checks, "S09x", "SvcStopSelfGone.onStartCommand", "id=1 flags=0 cmd=none", 1);
                expectCount(checks, "S09x", "SvcStopSelfGone.onStartCommand", "id=2 flags=0 cmd=stopResNow", 1);
                expectCount(checks, "S09x", "SvcStopSelfGone.onStartCommand", "id=3 flags=0 cmd=none", 1);
                // The one thing the child could get wrong alone: answering true for a
                // report it could not deliver.
                expectCount(checks, "S09x", "SvcStopSelfGone.stopSelfResult",
                    "startId=2 ret=false (no control link", 1);
                expectCount(checks, "S09x", "SvcStopSelfGone.stopSelfResult", "", 1);
                // ... and nothing was stopped: the record kept serving (start #3 is the
                // same instance's third start id) and no teardown ever arrived.
                expectCount(checks, "S09x", "SvcStopSelfGone.onDestroy", "", 0);
                expectBeforeContaining(checks, "S09x", "SvcStopSelfGone.stopSelfResult", "ret=false",
                    "SvcStopSelfGone.onStartCommand", "id=3 flags=0 cmd=none");
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S10 - START_STICKY: the record survives the death of its child, with a bound
    //       client and after that client left
    // ---------------------------------------------------------------------
    private Scenario s10() {
        final Scenario row = new Scenario("S10").budget(45000);
        final Class<?> cls = SvcSurvivingClient.class;
        final String process = processOf(cls);
        final Intent intent = svc(cls);
        final Connection survivor = new Connection("S10", "survivor");
        final int[] afterFirstDeath = new int[4];
        final int[] afterUnbind = new int[4];

        start(row, "start1", intent, "(no cmd: onStartCommand() returns START_STICKY)");
        row.observe(500);
        bindAuto(row, "bind1", intent, survivor, "survivor");
        row.observe(500);
        // Kill early: the first restart waits for 2 x the age of the delivered
        // start, so a short run keeps the window that follows short too.
        kill(row, "kill1", cls, "the started sticky record must come back");
        observes(row, 2, 2500);
        snapshot(row, "afterFirstDeath", "after the death and the restart", survivor,
            afterFirstDeath);
        unbind(row, "unbind1", survivor, "the client leaves");
        row.observe(800);
        snapshot(row, "afterUnbind", "the client has left", survivor, afterUnbind);
        kill(row, "kill2", cls, "a started sticky record restarts without any client");
        observes(row, 2, 2500);
        row.observe(1500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S10", "SvcSurvivingClient.onCreate", "", 3);
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=1 flags=0 cmd=none", 1);
                // START_STICKY redelivers a FABRICATED null intent: cmd=none, flags=0.
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=2 flags=0 cmd=none", 1);
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=3 flags=0 cmd=none", 1);
                EventLog.event("S10", "verdict.flags", "delivered start flags="
                    + flagsSequence("S10", cls));
                // A death is a disconnect, never a binding-died (S12's rule).
                checks.eq("after the first death: connects", 2, afterFirstDeath[0]);
                checks.eq("after the first death: disconnects", 1, afterFirstDeath[1]);
                checks.eq("after the first death: binding-died", 0, afterFirstDeath[2]);
                // The client left before the second death, so it is never called again.
                checks.eq("after the unbind: connects (unchanged)", afterFirstDeath[0],
                    afterUnbind[0]);
                checks.eq("after the unbind: disconnects (unchanged)", afterFirstDeath[1],
                    afterUnbind[1]);
                checks.eq("after the unbind: binding-died (unchanged)", afterFirstDeath[2],
                    afterUnbind[2]);
                checks.eq("final connects", afterUnbind[0], survivor.connects);
                checks.eq("final disconnects", afterUnbind[1], survivor.disconnects);
                checks.eq("final binding-died", afterUnbind[2], survivor.dieds);
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("S10 needs the fake backend to observe the sticky restart");
                } else {
                    checks.ok("a started START_STICKY record must be up again after the second kill,"
                        + " observed up=" + fake.isProcessUp(process), fake.isProcessUp(process));
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S10 (realhost arm) - the same START_STICKY restart policy, with the child
    //       exiting ITSELF instead of being killed through the fake seam
    // ---------------------------------------------------------------------
    /**
     * No {@code kill()} anywhere: this arm's deaths are the child's own
     * {@code cmd=die}, which is a real death for ATLProcessHost.startReaper
     * (teardown(childDied=true) -> the production ProcessDeathListener), so the
     * restart policy under test is production's, not a synthesized callback.
     * The observation windows cover the same planRestart delay the fake arm's
     * kill produces. Every verdict anchor is the merged view, so an in-loader
     * instance would be visible as a local event and FAIL the row.
     */
    private Scenario s10Real() {
        final Scenario row = new Scenario("S10").budget(60000);
        final Class<?> cls = SvcSurvivingClient.class;
        final String process = processOf(cls);
        final Intent intent = svc(cls);
        final Connection survivor = new Connection("S10", "survivor");
        final int[] afterDeath = new int[4];
        final int[] afterUnbind = new int[4];

        bindAuto(row, "bind1", intent, survivor, "survivor (AUTO_CREATE: the first child)");
        row.observe(1500);
        start(row, "start1", intent.putExtra("cmd", "die"),
            "#1: the child exits itself while the client is bound");
        observes(row, 2, 3000);
        snapshot(row, "afterDeath", "after the death and the restart", survivor, afterDeath);
        unbind(row, "unbind1", survivor, "the client leaves");
        row.observe(800);
        snapshot(row, "afterUnbind", "the client has left", survivor, afterUnbind);
        start(row, "start2", intent.putExtra("cmd", "die"),
            "#2: the child exits itself with no client");
        observes(row, 2, 3000);
        row.observe(1500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S10", "SvcSurvivingClient.onCreate", "", 3);
                // The fixture's own self-exit marker: two deaths, both the child's.
                expectCount(checks, "S10", "SvcSurvivingClient.die", "", 2);
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=1 flags=0 cmd=die", 1);
                // START_STICKY redelivers a FABRICATED null intent: cmd=none, flags=0.
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=2 flags=0 cmd=none", 1);
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=3 flags=0 cmd=die", 1);
                expectCount(checks, "S10", "SvcSurvivingClient.onStartCommand",
                    "id=4 flags=0 cmd=none", 1);
                // Zero onDestroy, and that is the correct count: AOSP dispatches a
                // service's onDestroy only while its host process still has a live
                // binder - ActiveServices.java:6964 `isThreadReady()` guards
                // `scheduleStopService(r)` at :6992 - and a record whose process died
                // is cleaned up through serviceProcessGoneLocked(:7334) instead. A
                // death is never an onDestroy (S12/S13c's rule).
                expectCount(checks, "S10", "SvcSurvivingClient.onDestroy", "", 0);
                checks.eq("S10 (real): no in-loader instance may serve the component", 0,
                    EventLog.countLocal("S10", "SvcSurvivingClient."));
                int[] pids = EventLog.foreignPids("S10", "SvcSurvivingClient.");
                checks.eq("three generations ran in three distinct spawned children", 3,
                    pids.length);
                checks.ok("no generation may run in the loader (pids " + pidsText(pids)
                    + ", loader pid " + EventLog.pid() + ")",
                    !containsPid(pids, EventLog.pid()));
                // Death 1 (bound): a disconnect, then the restart reconnects.
                checks.eq("after the death and the restart: connects", 2, afterDeath[0]);
                checks.eq("after the death and the restart: disconnects", 1, afterDeath[1]);
                checks.eq("after the death and the restart: binding-died", 0, afterDeath[2]);
                // The client left before the second death: it is never called again.
                checks.eq("after the unbind: connects (unchanged)", afterDeath[0], afterUnbind[0]);
                checks.eq("after the unbind: disconnects (unchanged)", afterDeath[1],
                    afterUnbind[1]);
                checks.eq("after the unbind: binding-died (unchanged)", afterDeath[2],
                    afterUnbind[2]);
                checks.eq("final connects (the client was gone before the second death)",
                    afterDeath[0], survivor.connects);
                checks.eq("final disconnects (the client was gone before the second death)",
                    afterDeath[1], survivor.disconnects);
                checks.eq("final binding-died (the client was gone before the second death)",
                    afterDeath[2], survivor.dieds);
                EventLog.event("S10", "verdict.flags", "delivered start flags="
                    + flagsSequence("S10", cls));
                Liveness live = REAL.liveness(process,
                    "S10 needs the real process host to observe the sticky restart");
                if (!live.usable) {
                    checks.fail(live.why);
                } else {
                    checks.ok("a started START_STICKY record must be up again after the second death,"
                        + " observed up=" + live.up, live.up);
                }
            }
        });
        return row;
    }

    /** A pid array rendered for a failure message. */
    private static String pidsText(int[] pids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pids.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pids[i]);
        }
        return sb.append("]").toString();
    }

    private static boolean containsPid(int[] pids, int pid) {
        for (int i = 0; i < pids.length; i++) {
            if (pids[i] == pid) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // S11a - a child-originated stopSelf() drives the authoritative record
    // ---------------------------------------------------------------------
    private Scenario s11a() {
        final Scenario row = new Scenario("S11a").budget(30000);
        final Class<?> cls = SvcRemoteStopSelf.class;
        final String process = processOf(cls);
        final Intent plain = svc(cls);
        final Intent stopNow = svc(cls).putExtra("cmd", "stopNow");

        start(row, "start1", plain, "#1");
        row.observe(800);
        start(row, "start2", plain, "#2");
        row.observe(800);
        start(row, "start3", stopNow, "#3 (stopSelf() inside the child)");
        row.observe(2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S11a", "SvcRemoteStopSelf.onCreate", "", 1);
                expectCount(checks, "S11a", "SvcRemoteStopSelf.onStartCommand", "", 3);
                expectCount(checks, "S11a", "SvcRemoteStopSelf.onStartCommand",
                    "id=3 flags=0 cmd=stopNow", 1);
                expectCount(checks, "S11a", "SvcRemoteStopSelf.stopSelf", "startId=current", 1);
                expectBefore(checks, "S11a", "SvcRemoteStopSelf.stopSelf", "startId=current",
                    "SvcRemoteStopSelf.onDestroy", "");
                expectCount(checks, "S11a", "SvcRemoteStopSelf.onDestroy", "", 1);
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("S11a needs the fake backend to observe the teardown");
                } else {
                    checks.ok("the hosting process must be gone after the child stopped itself,"
                        + " observed up=" + fake.isProcessUp(process), !fake.isProcessUp(process));
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S11b - the three spawn faults: none of them may run service code or fake a connect
    // ---------------------------------------------------------------------
    private Scenario s11b() {
        final Scenario row = new Scenario("S11b").budget(60000);
        final Class<?> spawnCls = SvcRemoteFault.class;
        final Class<?> crashCls = SvcRemoteFaultCrash.class;
        final Class<?> stallCls = SvcRemoteFaultStall.class;
        final Connection spawnClient = new Connection("S11b", "spawn");
        final Connection crashClient = new Connection("S11b", "crash");
        final Connection stallClient = new Connection("S11b", "stall");
        final int[] spawnCounters = new int[4];
        final int[] crashCounters = new int[4];
        final int[] stallCounters = new int[4];

        // Arm 1: the gate launch already armed `spawn` (ATL_SERVICE_PROCESS_FAULT=spawn),
        // one-shot, so it is consumed by this, the next spawn of any process.
        bindAuto(row, "bindSpawn", svc(spawnCls), spawnClient, "spawn fault (:s11b)");
        row.observe(2500);
        snapshot(row, "afterSpawnFault", "spawn failure answered", spawnClient, spawnCounters);

        // Arm 2: crash-before-ready - the row arms it for one process only, so a
        // gate-wide env var of another row cannot consume it.
        row.step(new Action() {
            public void run(Checks checks) {
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("crash-before-ready needs the fake backend");
                    return;
                }
                String spec = "crash-before-ready:" + processOf(crashCls);
                EventLog.event("S11b", "arm", spec);
                fake.armProcessFault(spec);
            }
        });
        bindAuto(row, "bindCrash", svc(crashCls), crashClient, "crash-before-ready (:s11bc)");
        observes(row, 2, 2500);
        snapshot(row, "afterCrashArm", "crash-before-ready answered by a restart", crashClient,
            crashCounters);

        // Arm 3: stall-before-ready - the op stays unanswered, so the client must
        // never be called and the component must never run.
        row.step(new Action() {
            public void run(Checks checks) {
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("stall-before-ready needs the fake backend");
                    return;
                }
                String spec = "stall-before-ready:" + processOf(stallCls);
                EventLog.event("S11b", "arm", spec);
                fake.armProcessFault(spec);
            }
        });
        bindAuto(row, "bindStall", svc(stallCls), stallClient, "stall-before-ready (:s11bs)");
        observes(row, 2, 2500);
        snapshot(row, "afterStallArm", "stall-before-ready never answered", stallClient,
            stallCounters);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                // Arm 1 and arm 3 never get an instance; arm 2 gets exactly one, in
                // the process the restart spawned (the fault killed the first spawn
                // before its first transaction). The tag anchor carries its dot, so
                // "SvcRemoteFault." does not silently cover the Crash/Stall classes.
                expectNeverRan(checks, "S11b", "SvcRemoteFault.");
                expectNeverRan(checks, "S11b", "SvcRemoteFaultStall.");
                expectCount(checks, "S11b", "SvcRemoteFaultCrash.onCreate", "", 1);
                expectCount(checks, "S11b", "SvcRemoteFaultCrash.onBind", "", 1);

                // Arm 1: a spawn failure is reported through onServiceDisconnected to
                // the undelivered client - never as a connect, never as a
                // binding-died (the approved DO_SPAWN_FAILURE deviation).
                checks.eq("spawn fault: connects", 0, spawnCounters[0]);
                expectAtLeast(checks, "spawn fault: disconnects (the client learns its request died)",
                    spawnCounters[1], 1);
                checks.eq("spawn fault: binding-died", 0, spawnCounters[2]);
                checks.eq("spawn fault: null bindings", 0, spawnCounters[3]);
                EventLog.event("S11b", "verdict.spawnFault", spawnClient.counters());

                // Arm 2: the first spawn died before its first transaction, so the
                // record was restarted: the client's single connect comes from that
                // restart (one restart delay after the bind, never from the first
                // spawn), and nothing was delivered before the death, so there is no
                // disconnect to report either.
                checks.eq("crash-before-ready: connects (the restart delivered)", 1,
                    crashCounters[0]);
                checks.eq("crash-before-ready: disconnects (nothing was delivered yet)", 0,
                    crashCounters[1]);
                checks.eq("crash-before-ready: binding-died", 0, crashCounters[2]);
                checks.eq("crash-before-ready: null bindings", 0, crashCounters[3]);
                long bindAt = EventLog.uptimeOfNthContaining("S11b", "call.bindCrash", "", 0);
                long connectAt = connectAt("S11b", "crash", 0);
                if (bindAt < 0 || connectAt < 0) {
                    checks.fail("crash-before-ready: bind at " + bindAt + " and connect at "
                        + connectAt + " must both be recorded");
                } else {
                    expectAtLeast(checks, "crash-before-ready: the connect came from the scheduled"
                        + " restart, not from the first spawn (connect - bind)",
                        connectAt - bindAt, 250);
                }

                // Arm 3: no answer at all - no callback of any kind.
                checks.eq("stall-before-ready: connects", 0, stallCounters[0]);
                checks.eq("stall-before-ready: disconnects", 0, stallCounters[1]);
                checks.eq("stall-before-ready: binding-died", 0, stallCounters[2]);
                checks.eq("stall-before-ready: null bindings", 0, stallCounters[3]);
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S11c - onBind() == null remotely is a null binding, never a connect
    // ---------------------------------------------------------------------
    private Scenario s11c() {
        final Scenario row = new Scenario("S11c").budget(30000);
        final Class<?> cls = SvcRemoteNullBind.class;
        final Intent intent = svc(cls);
        final Connection client = new Connection("S11c", "null");

        bindAuto(row, "bindNull", intent, client, "null onBind result");
        row.observe(2500);
        unbind(row, "unbindNull", client, "after the null binding");
        row.observe(1500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                checks.eq("null client null-bindings", 1, client.nulls);
                checks.eq("null client connects", 0, client.connects);
                checks.eq("null client disconnects", 0, client.disconnects);
                checks.eq("null client binding-died", 0, client.dieds);
                expectCountContaining(checks, "S11c", "null.onNullBinding",
                    "comp=SvcRemoteNullBind", 1);
                expectCount(checks, "S11c", "SvcRemoteNullBind.onBind", "action=none", 1);
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S11d - onUnbind() == true arms onRebind() across the remote boundary
    // ---------------------------------------------------------------------
    private Scenario s11d() {
        final Scenario row = new Scenario("S11d").budget(30000);
        final Class<?> cls = SvcRemoteRebind.class;
        final Intent intent = svc(cls);
        final Connection client = new Connection("S11d", "rebind");

        start(row, "startRebind", intent, "(a started instance survives an unbind())");
        row.observe(800);
        bindAuto(row, "bindRebind1", intent, client, "#1");
        row.observe(1500);
        unbind(row, "unbindRebind1", client, "#1 (onUnbind() returns true)");
        row.observe(800);
        bindAuto(row, "bindRebind2", intent, client, "#2 (same identity: onRebind expected)");
        row.observe(2500);
        unbind(row, "unbindRebind2", client, "#2");
        row.observe(800);
        stop(row, "stopRebind", intent, "(the started instance dies here)");
        row.observe(2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectCount(checks, "S11d", "SvcRemoteRebind.onBind", "action=none", 1);
                expectCount(checks, "S11d", "SvcRemoteRebind.onUnbind", "", 2);
                expectCount(checks, "S11d", "SvcRemoteRebind.onRebind", "action=none", 1);
                expectBefore(checks, "S11d", "SvcRemoteRebind.onUnbind", "",
                    "SvcRemoteRebind.onRebind", "");
                expectCount(checks, "S11d", "SvcRemoteRebind.onDestroy", "", 1);
                checks.eq("client connects", 2, client.connects);
                checks.eq("client binding-died", 0, client.dieds);
                checks.ok("the rebind must report the SAME binder object, observed "
                    + client.binders, client.binders.size() == 2
                    && client.binders.get(0) == client.binders.get(1));
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S12 - a death is onServiceDisconnected; the bound-crash cap tears the binding
    //       down; REDELIVER_INTENT redelivers the same intent
    // ---------------------------------------------------------------------
    private Scenario s12() {
        final Scenario row = new Scenario("S12").budget(60000);
        final Class<?> cls = SvcRedeliver.class;
        final Intent intent = svc(cls).putExtra("cmd", "redeliver");
        final Connection client = new Connection("S12", "bound");
        final int[] afterConnect = new int[4];
        final int[] afterDeath1 = new int[4];
        final int[] afterDeath2 = new int[4];
        final int[] afterDeath3 = new int[4];

        start(row, "start1", intent, "(REDELIVER_INTENT: the intent must come back)");
        row.observe(500);
        bindAuto(row, "bind1", intent, client, "bound client");
        row.observe(500);
        snapshot(row, "afterConnect", "connected", client, afterConnect);

        kill(row, "kill1", cls, "death 1: a disconnect, never a binding-died");
        observes(row, 2, 2500);
        snapshot(row, "afterDeath1", "death 1 answered by a restart", client, afterDeath1);

        kill(row, "kill2", cls, "death 2: still under the bound-crash cap");
        observes(row, 2, 2500);
        snapshot(row, "afterDeath2", "death 2 answered by a restart", client, afterDeath2);

        kill(row, "kill3", cls, "death 3: the bound-crash retry cap (3) is reached");
        row.observe(2500);
        row.observe(1500);
        snapshot(row, "afterDeath3", "the torn-down binding", client, afterDeath3);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                checks.eq("after the connect: connects", 1, afterConnect[0]);
                checks.eq("after the connect: disconnects", 0, afterConnect[1]);
                // Death 1: exactly the AOSP death callback set - no binding-died.
                checks.eq("death 1: connects (reconnected after the restart)", 2, afterDeath1[0]);
                checks.eq("death 1: disconnects", 1, afterDeath1[1]);
                checks.eq("death 1: binding-died (a death is NOT a binding-died)", 0, afterDeath1[2]);
                // Death 2: the same, one generation later.
                checks.eq("death 2: connects", 3, afterDeath2[0]);
                checks.eq("death 2: disconnects", 2, afterDeath2[1]);
                checks.eq("death 2: binding-died", 0, afterDeath2[2]);
                // Death 3: the cap is reached, so the record is torn down instead.
                checks.eq("death 3: connects (no fourth connect)", 3, afterDeath3[0]);
                checks.eq("death 3: disconnects", 3, afterDeath3[1]);
                checks.eq("death 3: binding-died", 1, afterDeath3[2]);
                expectBefore(checks, "S12", "bound.onServiceDisconnected", "",
                    "bound.onBindingDied", "");
                // The redelivery is the SAME intent: its extra survived the deaths.
                expectCountContaining(checks, "S12", "SvcRedeliver.onStartCommand",
                    "cmd=redeliver", 3);
                expectCount(checks, "S12", "SvcRedeliver.onCreate", "", 3);
                // Zero onDestroy, and that is the correct count: AOSP dispatches the
                // service's onDestroy only while its host process still has a live
                // binder - ActiveServices.java:6964 `isThreadReady()` guards
                // `scheduleStopService(r)` at :6992 - and a record whose process died is
                // cleaned up through serviceProcessGoneLocked(:7334) instead. The last
                // instance died with its process, so the torn-down record must not
                // synthesize an onDestroy for an instance that no longer exists (the
                // teardown is visible to the client as onServiceDisconnected +
                // onBindingDied, asserted above).
                expectCount(checks, "S12", "SvcRedeliver.onDestroy", "", 0);
                EventLog.event("S12", "verdict.flags", "delivered start flags="
                    + flagsSequence("S12", cls));
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("S12 needs the fake backend to kill the child");
                } else {
                    checks.ok("the host must be down after the cap tore the record down, observed up="
                        + fake.isProcessUp(processOf(cls)), !fake.isProcessUp(processOf(cls)));
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S13a - the restart ladder (ladder gate: MIN_CRASH_INTERVAL_MS below the kills)
    // ---------------------------------------------------------------------
    private Scenario s13a() {
        final Scenario row = new Scenario("S13a").budget(60000);
        final Class<?> cls = SvcLadder.class;
        final Intent intent = svc(cls);
        final Connection client = new Connection("S13a", "ladder");

        bindAuto(row, "bind1", intent, client, "bind-only record: AUTO_CREATE");
        row.observe(1500);
        kill(row, "kill1", cls, "crash 1: the first restart delay");
        row.observe(1500);
        kill(row, "kill2", cls, "crash 2: the x4 branch");
        row.observe(2500);
        kill(row, "kill3", cls, "crash 3: the x4 branch again");
        observes(row, 4, 2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                checks.eq("client connects (1 + 3 restarts)", 4, client.connects);
                checks.eq("client disconnects (one per death)", 3, client.disconnects);
                checks.eq("client binding-died (under the cap: never)", 0, client.dieds);
                expectCount(checks, "S13a", "SvcLadder.onCreate", "", 4);
                expectCount(checks, "S13a", "SvcLadder.onBind", "", 4);
                // The ladder is measured from each kill to the reconnect it caused:
                // the first delay is the minDuration branch (RESTART_DURATION_MS =
                // 300 ms), the next two are the x4 branch (>= 1200 ms, which a
                // ">= 300" check could not tell from the first).
                long[] connects = new long[4];
                for (int i = 0; i < connects.length; i++) {
                    connects[i] = connectAt("S13a", "ladder", i);
                }
                long[] kills = new long[3];
                for (int i = 0; i < kills.length; i++) {
                    kills[i] = killAt("S13a", i);
                }
                if (connects[0] < 0 || connects[1] < 0 || connects[2] < 0 || connects[3] < 0
                    || kills[0] < 0 || kills[1] < 0 || kills[2] < 0) {
                    checks.fail("the ladder needs four connects and three kills, observed "
                        + connects[0] + "," + connects[1] + "," + connects[2] + "," + connects[3]
                        + " kills " + kills[0] + "," + kills[1] + "," + kills[2]);
                } else {
                    expectBetween(checks, "restart delta 1", connects[1] - kills[0], 300, 700);
                    expectBetween(checks, "restart delta 2", connects[2] - kills[1], 1200, 2000);
                    expectBetween(checks, "restart delta 3", connects[3] - kills[2], 4800, 6000);
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S13b - the acknowledged-redelivery cap: the delivery runs out (caps gate)
    // ---------------------------------------------------------------------
    private Scenario s13b() {
        final Scenario row = new Scenario("S13b").budget(90000);
        final Class<?> cls = SvcAckCap.class;
        final Intent intent = svc(cls).putExtra("cmd", "ackcap");
        final Connection client = new Connection("S13b", "bound");

        start(row, "start1", intent, "(REDELIVER_INTENT: six acknowledged redeliveries, no more)");
        row.observe(500);
        bindAuto(row, "bind1", intent, client, "bound client");
        row.observe(500);
        // Under this gate's env the delay grows by boundCrashRestartDurationMs per
        // death after the first (1000, 2000, 3000, 4000, 5000 ms), so each kill has
        // to come after the restart it follows and the windows grow with the ladder.
        kill(row, "kill1", cls, "ack 1 of 6");
        row.observe(2500);
        row.observe(1500);
        kill(row, "kill2", cls, "ack 2 of 6");
        row.observe(2500);
        row.observe(1500);
        kill(row, "kill3", cls, "ack 3 of 6");
        observes(row, 2, 2500);
        kill(row, "kill4", cls, "ack 4 of 6");
        observes(row, 3, 2500);
        kill(row, "kill5", cls, "ack 5 of 6");
        observes(row, 3, 2500);
        kill(row, "kill6", cls, "ack 6 of 6: the cap is reached");
        observes(row, 3, 2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                int deliveries = EventLog.count("S13b", "SvcAckCap.onStartCommand");
                checks.eq("delivered starts (MAX_DONE_EXECUTING_COUNT is 6)", 6, deliveries);
                ArrayList<String> details =
                    EventLog.sequenceDetailed("S13b", "SvcAckCap.onStartCommand");
                if (!details.isEmpty()) {
                    checks.eq("the first delivery is not a redelivery", "0",
                        field(details.get(0), "flags="));
                }
                EventLog.event("S13b", "verdict.flags", "delivered start flags="
                    + flagsSequence("S13b", cls));
                EventLog.event("S13b", "verdict.counters", client.counters());
                // Kills that landed while the process was down would be no deaths at
                // all, and the cap would look reached for the wrong reason: one
                // disconnect per death is what makes the six deliveries real.
                checks.eq("client disconnects (one real death per kill)", 6, client.disconnects);
                checks.eq("client binding-died (the crash cap stays at its default 16)",
                    0, client.dieds);
                expectAtLeast(checks, "client connects (the binding survived every death)",
                    client.connects, 6);
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S13c - START_STICKY + bound client: the bound-crash cap tears it down
    // ---------------------------------------------------------------------
    private Scenario s13c() {
        final Scenario row = new Scenario("S13c").budget(45000);
        final Class<?> cls = SvcCrashCap.class;
        final Intent intent = svc(cls);
        final Connection client = new Connection("S13c", "bound");
        final int[] afterDeath1 = new int[4];
        final int[] afterDeath2 = new int[4];
        final int[] afterDeath3 = new int[4];

        start(row, "start1", intent, "(START_STICKY, no cmd: the restart's intent is fabricated)");
        row.observe(500);
        bindAuto(row, "bind1", intent, client, "bound client");
        row.observe(500);

        kill(row, "kill1", cls, "death 1: sticky restart with a null intent");
        observes(row, 2, 2500);
        snapshot(row, "afterDeath1", "death 1 answered by a restart", client, afterDeath1);

        kill(row, "kill2", cls, "death 2: still under the bound-crash cap");
        observes(row, 2, 2500);
        snapshot(row, "afterDeath2", "death 2 answered by a restart", client, afterDeath2);

        kill(row, "kill3", cls, "death 3: the bound-crash retry cap (3) is reached");
        row.observe(2500);
        row.observe(1500);
        snapshot(row, "afterDeath3", "the torn-down binding", client, afterDeath3);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                checks.eq("death 1: connects", 2, afterDeath1[0]);
                checks.eq("death 1: disconnects", 1, afterDeath1[1]);
                checks.eq("death 1: binding-died", 0, afterDeath1[2]);
                checks.eq("death 2: connects", 3, afterDeath2[0]);
                checks.eq("death 2: disconnects", 2, afterDeath2[1]);
                checks.eq("death 2: binding-died", 0, afterDeath2[2]);
                checks.eq("death 3: connects (no fourth connect)", 3, afterDeath3[0]);
                checks.eq("death 3: disconnects", 3, afterDeath3[1]);
                checks.eq("death 3: binding-died", 1, afterDeath3[2]);
                // START_STICKY restarts with a fabricated NULL intent: cmd=none and
                // flags 0, at a fresh start id - never the original intent.
                expectCount(checks, "S13c", "SvcCrashCap.onStartCommand", "id=1 flags=0 cmd=none", 1);
                expectCount(checks, "S13c", "SvcCrashCap.onStartCommand", "id=2 flags=0 cmd=none", 1);
                expectCount(checks, "S13c", "SvcCrashCap.onStartCommand", "id=3 flags=0 cmd=none", 1);
                expectCount(checks, "S13c", "SvcCrashCap.onCreate", "", 3);
                // Zero onDestroy: the cap tears the record down while its process is
                // already dead (ActiveServices.java:6964 guards the onDestroy dispatch
                // at :6992 by `isThreadReady()`; a dead-process record is cleaned up via
                // serviceProcessGoneLocked(:7334)), so there is no instance left to
                // destroy - the third death's teardown is a client-side event only.
                expectCount(checks, "S13c", "SvcCrashCap.onDestroy", "", 0);
                expectBefore(checks, "S13c", "bound.onServiceDisconnected", "",
                    "bound.onBindingDied", "");
                EventLog.event("S13c", "verdict.flags", "delivered start flags="
                    + flagsSequence("S13c", cls));
                ATLFakeProcessBackend fake = backend();
                if (fake == null) {
                    checks.fail("S13c needs the fake backend to kill the child");
                } else {
                    checks.ok("no fourth delivery may happen: the process must stay down, observed up="
                        + fake.isProcessUp(processOf(cls)), !fake.isProcessUp(processOf(cls)));
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S14c - a service CHILD refuses to place a service in another process
    // ---------------------------------------------------------------------
    private Scenario s14c() {
        final Scenario row = new Scenario("S14c").budget(30000);
        final Class<?> cls = SvcChildRefused.class;
        final Intent intent = svc(cls);
        final Connection client = new Connection("S14c", "childrefused");
        final String refusal = "Service-to-other-process bindings are not implemented";

        bindExpectedRefusal(row, "bindChild", intent, client, "bindService(remote, from a child)",
            refusal);
        startExpectedRefusal(row, "startChild", intent, "startService(remote, from a child)",
            refusal);
        row.observe(2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectNeverRan(checks, "S14c", "SvcChildRefused");
                checks.eq("client connects", 0, client.connects);
                checks.eq("client disconnects", 0, client.disconnects);
                checks.eq("client null bindings", 0, client.nulls);
                checks.eq("client binding-died", 0, client.dieds);
            }
        });
        return row;
    }

    // ---------------------------------------------------------------------
    // S15 - local and remote must produce the same trace for the same calls
    // ---------------------------------------------------------------------
    private Scenario s15() {
        final Scenario row = new Scenario("S15").budget(45000);
        final Intent localIntent = svc(SvcTraceLocal.class);
        final Intent remoteIntent = svc(SvcTraceRemote.class);
        final Connection localClient = new Connection("S15", "local");
        final Connection remoteClient = new Connection("S15", "remote");

        // The S02 call sequence, in the loader's own process.
        start(row, "startLocal", localIntent, "(in-loader)");
        row.observe(1500);
        bindAuto(row, "bindLocal", localIntent, localClient, "(in-loader)");
        row.observe(1500);
        stop(row, "stopLocal", localIntent, "(in-loader)");
        row.observe(1500);
        unbind(row, "unbindLocal", localClient, "(in-loader)");
        row.observe(1500);

        // The same sequence through the process backend.
        start(row, "startRemote", remoteIntent, "(remote :s15)");
        row.observe(2500);
        bindAuto(row, "bindRemote", remoteIntent, remoteClient, "(remote :s15)");
        row.observe(2500);
        stop(row, "stopRemote", remoteIntent, "(remote :s15)");
        row.observe(2500);
        unbind(row, "unbindRemote", remoteClient, "(remote :s15)");
        row.observe(2500);

        row.verdictChecks.add(new Action() {
            public void run(Checks checks) {
                expectSameTraces(checks, "S15", "SvcTraceLocal.", "SvcTraceRemote.");
                checks.eq("local client connects", 1, localClient.connects);
                checks.eq("remote client connects", 1, remoteClient.connects);
            }
        });
        return row;
    }
}