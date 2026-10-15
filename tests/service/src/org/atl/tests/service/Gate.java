/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B): the row model and the assertion helpers every
 * gate is written with.
 *
 * This is the part of ControlActivity that the local gate (rows S01-S08, S14a,
 * S14b) and the remote gates (rows S09-S13, S14c, S15, RemoteScenarios) must
 * agree on: one Scenario is an ordered list of Steps, a Step is a trigger plus
 * a delay, a verdict is one Checks. The step pump itself stays in ControlActivity
 * (it owns the main Handler), and each gate builds its own rows.
 *
 * The helpers below only read the EventLog of production's own trace, so an
 * assertion can never be satisfied by the probe's summary of itself.
 */
package org.atl.tests.service;

import java.util.ArrayList;
import java.util.Arrays;

final class Gate {

    /** Delay between two steps of a row: production's main-looper work runs in between. */
    static final long STEP_MS = 250L;
    /** Default closing observation window of a row (what lets "never" be asserted). */
    static final long SETTLE_MS = 800L;
    /** Default row budget; a row that needs longer calls {@link Scenario#budget}. */
    static final long DEFAULT_BUDGET_MS = 20000L;

    private Gate() {
    }

    /** One step of a row; a null {@code action} means "observation window only". */
    interface Action {
        void run(Checks checks) throws Exception;
    }

    static final class Step {
        final long delay;
        final Action action;

        Step(long delay, Action action) {
            this.delay = delay;
            this.action = action;
        }
    }

    /** One acceptance row: ordered steps, its own Checks, exactly one verdict. */
    static final class Scenario {
        final String id;
        final Checks checks;
        final ArrayList<Step> steps = new ArrayList<Step>();
        /** Assertions over the whole row, run once after the last observation window. */
        final ArrayList<Action> verdictChecks = new ArrayList<Action>();
        boolean verdictDone;
        long budgetMs = DEFAULT_BUDGET_MS;

        Scenario(String id) {
            this.id = id;
            this.checks = new Checks(id);
        }

        void step(Action action) {
            steps.add(new Step(STEP_MS, action));
        }

        /** The row's closing observation window: no action, no new assertion. */
        void observe() {
            steps.add(new Step(SETTLE_MS, null));
        }

        /** Closing observation window of a row that asserts "never" (>= 1 s). */
        void observe(long ms) {
            steps.add(new Step(ms, null));
        }

        /**
         * Row budget: restarts are reported by the backend after their AOSP delay
         * (RESTART_DURATION_MS, x4 per crash), so rows that kill a process more
         * than twice need more than the default budget.
         */
        Scenario budget(long ms) {
            this.budgetMs = ms;
            return this;
        }
    }

    /** One production call whose outcome a row asserts: return value, or throwable. */
    interface Attempt {
        Object run() throws Exception;
    }

    static final class CallOutcome {
        final String label;
        Throwable thrown;
        String returned;

        CallOutcome(String label) {
            this.label = label;
        }

        String describe() {
            return thrown != null
                ? "threw " + thrown.getClass().getName() + ": " + thrown.getMessage()
                : "returned " + returned;
        }
    }

    /** Runs one production call and records its outcome as a TRACE line of the row. */
    static CallOutcome attempt(String id, String label, Attempt attempt) {
        CallOutcome outcome = new CallOutcome(label);
        try {
            outcome.returned = String.valueOf(attempt.run());
        } catch (Throwable t) {
            outcome.thrown = t;
        }
        EventLog.event(id, "call." + label, outcome.describe());
        return outcome;
    }

    /** Ordered containment: the first event of the row must precede the second. */
    static void expectBefore(Checks checks, String id, String what, String detail,
                             String laterWhat, String laterDetail) {
        int at = EventLog.indexOfWhere(id, what, detail);
        int later = EventLog.indexOfWhere(id, laterWhat, laterDetail);
        checks.ok(what + "[" + detail + "] #" + at + " must precede "
            + laterWhat + "[" + laterDetail + "] #" + later, at >= 0 && later > at);
    }

    /**
     * Ordered containment with containing anchors: production's detail strings
     * gain fields over time, so the anchor is "the detail contains this", not
     * "the detail starts with this".
     */
    static void expectBeforeContaining(Checks checks, String id, String what, String substring,
                                       String laterWhat, String laterSubstring) {
        int at = EventLog.indexOfContaining(id, what, substring);
        int later = EventLog.indexOfContaining(id, laterWhat, laterSubstring);
        checks.ok(what + "\"" + substring + "\" #" + at + " must precede "
            + laterWhat + "\"" + laterSubstring + "\" #" + later, at >= 0 && later > at);
    }

    /**
     * Ordered containment where the later event is the {@code n}-th (0-based) one:
     * "the death notification precedes the *second* connect", not "the first".
     */
    static void expectNthContainingAfter(Checks checks, String id, String what, String substring,
                                         String laterWhat, String laterSubstring, int laterN) {
        int at = EventLog.indexOfContaining(id, what, substring);
        int later = EventLog.indexOfNthContaining(id, laterWhat, laterSubstring, laterN);
        checks.ok(what + "\"" + substring + "\" #" + at + " must precede "
            + laterWhat + "\"" + laterSubstring + "\" #" + laterN + " #" + later,
            at >= 0 && later > at);
    }

    static void expectCount(Checks checks, String id, String what, String detailPrefix,
                            int expected) {
        checks.eq(what + "[" + detailPrefix + "] count", expected,
            EventLog.countWhere(id, what, detailPrefix));
    }

    /** Count with a containing anchor: the anchor must match the event wherever it sits in the detail. */
    static void expectCountContaining(Checks checks, String id, String what, String substring,
                                      int expected) {
        checks.eq(what + "\"" + substring + "\" count", expected,
            EventLog.countContaining(id, what, substring));
    }

    /** A measured duration must be at least {@code floor} ms: the restart ladder of rows S13a/S13c. */
    static void expectAtLeast(Checks checks, String what, long actual, long floor) {
        checks.ok(what + " measured " + actual + "ms must be >= " + floor + "ms", actual >= floor);
    }

    /** Exact service-side callback sequence, in order, nothing else. */
    static void expectSequence(Checks checks, String id, String tagPrefix, String... expected) {
        checks.ok(tagPrefix + " callbacks must be exactly " + Arrays.toString(expected)
            + " observed " + EventLog.sequenceText(id, tagPrefix),
            EventLog.sequenceEquals(id, tagPrefix, expected));
    }

    /** Ordered containment of a service-side callback sequence. */
    static void expectSubsequence(Checks checks, String id, String tagPrefix, String... expected) {
        checks.ok(tagPrefix + " callbacks must contain " + Arrays.toString(expected)
            + " in order, observed " + EventLog.sequenceText(id, tagPrefix),
            EventLog.isSubsequence(id, tagPrefix, expected));
    }

    /**
     * Trace parity (row S15): the same call sequence must produce the same
     * callback order and the same callback details through the local path and
     * through the remote path. The component tag is stripped before comparing,
     * because that, and only that, is what legitimately differs.
     */
    static void expectSameTraces(Checks checks, String id, String tagA, String tagB) {
        String a = strip(id, EventLog.sequenceDetailed(id, tagA), tagA);
        String b = strip(id, EventLog.sequenceDetailed(id, tagB), tagB);
        checks.ok("local trace " + a + " and remote trace " + b
            + " must be identical up to the component tag", a.equals(b));
        checks.ok(tagA + " must have produced callbacks, observed " + a, !a.equals("[]"));
    }

    private static String strip(String id, ArrayList<String> traced, String tag) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < traced.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(traced.get(i).startsWith(tag) ? traced.get(i).substring(tag.length()) : traced.get(i));
        }
        return sb.append("]").toString();
    }

    /** The rows that refuse a component assert that none of its callbacks ever ran. */
    static void expectNeverRan(Checks checks, String id, String simpleName) {
        ArrayList<String> events = EventLog.sequence(id, simpleName);
        checks.eq(simpleName + " callbacks observed", 0, events.size());
        checks.ok(simpleName + " must never run, observed " + events, events.isEmpty());
    }

    /** Placement refusal: exact type and full message (ATLServiceController.java:223/:249). */
    static void expectUnsupported(Checks checks, String what, CallOutcome outcome,
                                  String message) {
        if (outcome.thrown == null) {
            checks.fail(what + " must be refused explicitly, observed " + outcome.describe());
            return;
        }
        checks.eq(what + " exception type", "java.lang.UnsupportedOperationException",
            outcome.thrown.getClass().getName());
        checks.ok(what + " must throw an UnsupportedOperationException",
            outcome.thrown instanceof UnsupportedOperationException);
        checks.eq(what + " exception message", message, outcome.thrown.getMessage());
    }

    /**
     * stopService() refusal check. Production resolves no placement on the
     * stopService() path - ATLServiceController.java:584 looks the component up in
     * the registry only and ATLServiceRegistry.java:627 returns running=false for a
     * component that was never placed - so the refusal here is that nothing is
     * stopped and nothing is started: the call must either throw the same exact
     * placement refusal (if a later production change resolves placement on
     * stopService too) or report false, and it must never report a stop it did not
     * perform. "The component never ran" is asserted separately (expectNeverRan).
     */
    static void expectStopRefusal(Checks checks, String what, CallOutcome outcome,
                                  String message) {
        if (outcome.thrown != null) {
            checks.eq(what + " exception type", "java.lang.UnsupportedOperationException",
                outcome.thrown.getClass().getName());
            checks.eq(what + " exception message", message, outcome.thrown.getMessage());
            return;
        }
        checks.eq(what + " must not stop or create anything; stopService returned",
            "false", outcome.returned);
    }

    /** Exception type and message prefix, e.g. java.lang.IllegalArgumentException / "Service not registered: ". */
    static void expectThrew(Checks checks, String what, CallOutcome outcome, String type,
                            String messagePrefix) {
        if (outcome.thrown == null) {
            checks.fail(what + " expected " + type + ", observed " + outcome.describe());
            return;
        }
        checks.eq(what + " exception type", type, outcome.thrown.getClass().getName());
        String message = outcome.thrown.getMessage();
        checks.ok(what + " message must start with [" + messagePrefix + "] observed[" + message + "]",
            message != null && message.startsWith(messagePrefix));
    }

    /** A call that must succeed and report {@code expected}. */
    static void expectReturned(Checks checks, String what, CallOutcome outcome,
                               String expected) {
        if (outcome.thrown != null) {
            checks.fail(what + " must not throw, observed " + outcome.describe());
            return;
        }
        checks.eq(what, expected, outcome.returned);
    }

    /** A call that must succeed (a ComponentName was resolved, or an int flag set). */
    static void expectAccepted(Checks checks, String what, CallOutcome outcome) {
        checks.ok(what + " must be accepted by the service manager, observed " + outcome.describe(),
            outcome.thrown == null && outcome.returned != null && !"null".equals(outcome.returned));
    }

    /** A void call that must not throw. */
    static void expectNoThrow(Checks checks, String what, CallOutcome outcome) {
        checks.ok(what + " must not throw, observed " + outcome.describe(), outcome.thrown == null);
    }
}
