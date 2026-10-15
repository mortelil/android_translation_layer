/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B, LOCAL gate): the single log records the ordering
 * that the acceptance rows assert (what happened, in what order, with what
 * detail), plus the wall-clock of every event, which rows S13a/S13c assert the
 * restart ladder against.
 *
 * The log goes to stdout as `<SEVERITY> <SCENARIO> <WHAT> <DETAIL>` lines, one
 * per event, and stays machine-greppable:
 *   SVC S01 TRACE ...     one event (t=<uptime ms>, the row/callback/step and its detail)
 *   SVC S01 PASS          the row's single verdict line
 *   SVC S01 FAIL <reason> the row's single verdict line
 *   SVC_GATE <name> begin / SVC_GATE_DONE <name>   gate framing
 * The rows are extracted per scenario by tests/service/run.sh, which greps
 * `^SVC <id> ` out of this stream.
 */
package org.atl.tests.service;

import android.content.ComponentName;
import android.os.SystemClock;
import java.util.ArrayList;

public final class EventLog {

    /** One ordered event: the acceptance row, the callback/step, and free-form evidence. */
    public static final class Entry {
        public final String scenario;
        public final String what;
        public final String detail;
        /** SystemClock.uptimeMillis() when the event was recorded: what rows S13a/S13c assert against. */
        public final long t;

        Entry(String scenario, String what, String detail, long t) {
            this.scenario = scenario;
            this.what = what;
            this.detail = detail;
            this.t = t;
        }

        public String render() {
            return detail.length() == 0 ? what : what + " " + detail;
        }
    }

    private static final ArrayList<Entry> entries = new ArrayList<Entry>();
    private static final Object lock = new Object();

    private EventLog() {
    }

    /** Records one event and echoes it as a TRACE line (autoflushed). */
    public static void event(String scenario, String what, String detail) {
        Entry entry = new Entry(scenario, what, detail == null ? "" : detail, SystemClock.uptimeMillis());
        synchronized (lock) {
            entries.add(entry);
        }
        line("SVC " + scenario + " TRACE t=" + entry.t + " " + entry.render());
    }

    /** Writes one whole stdout line; serialised so concurrent writers cannot interleave. */
    public static void line(String text) {
        synchronized (lock) {
            System.out.println(text);
            System.out.flush();
        }
    }

    private static ArrayList<Entry> snapshot() {
        synchronized (lock) {
            return new ArrayList<Entry>(entries);
        }
    }

    /** Number of events of {@code scenario} whose tag is exactly {@code what}. */
    public static int count(String scenario, String what) {
        int n = 0;
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario) && e.what.equals(what)) {
                n++;
            }
        }
        return n;
    }

    /** Number of events of {@code scenario}/{@code what} whose detail starts with {@code detailPrefix}. */
    public static int countWhere(String scenario, String what, String detailPrefix) {
        int n = 0;
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario) && e.what.equals(what) && e.detail.startsWith(detailPrefix)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Number of events of {@code scenario}/{@code what} whose detail contains {@code substring}.
     * Containing beats a prefix anchor whenever production appends to a detail: an
     * anchor that no longer sits at offset 0 must not silently match zero events.
     */
    public static int countContaining(String scenario, String what, String substring) {
        int n = 0;
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario) && e.what.equals(what) && e.detail.contains(substring)) {
                n++;
            }
        }
        return n;
    }

    /** The ordered {@code what} values of {@code scenario} whose tag starts with {@code tagPrefix}. */
    public static ArrayList<String> sequence(String scenario, String tagPrefix) {
        ArrayList<String> out = new ArrayList<String>();
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario) && e.what.startsWith(tagPrefix)) {
                out.add(e.what);
            }
        }
        return out;
    }

    /**
     * The ordered {@code what + " " + detail} values of {@code scenario} under
     * {@code tagPrefix}: how S15 compares the local and the remote trace of the
     * same call sequence, callback by callback and detail by detail.
     */
    public static ArrayList<String> sequenceDetailed(String scenario, String tagPrefix) {
        ArrayList<String> out = new ArrayList<String>();
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario) && e.what.startsWith(tagPrefix)) {
                out.add(e.render());
            }
        }
        return out;
    }

    /** {@link #sequence} rendered for a failure message. */
    public static String sequenceText(String scenario, String tagPrefix) {
        StringBuilder sb = new StringBuilder("[");
        ArrayList<String> seq = sequence(scenario, tagPrefix);
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(seq.get(i));
        }
        return sb.append("]").toString();
    }

    /** Exact ordered equality of the {@code what} values under {@code tagPrefix}. */
    public static boolean sequenceEquals(String scenario, String tagPrefix, String... expected) {
        ArrayList<String> actual = sequence(scenario, tagPrefix);
        if (actual.size() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!actual.get(i).equals(expected[i])) {
                return false;
            }
        }
        return true;
    }

    /** Ordered containment: {@code expected} appears in order, with other events in between allowed. */
    public static boolean isSubsequence(String scenario, String tagPrefix, String... expected) {
        ArrayList<String> actual = sequence(scenario, tagPrefix);
        int at = 0;
        for (String what : actual) {
            if (at < expected.length && what.equals(expected[at])) {
                at++;
            }
        }
        return at == expected.length;
    }

    /** Position of the first {@code scenario}/{@code what} event among that row's events, or -1. */
    public static int indexOf(String scenario, String what) {
        return indexOfWhere(scenario, what, "");
    }

    /** Position of the first {@code scenario}/{@code what} event with a matching detail prefix, or -1. */
    public static int indexOfWhere(String scenario, String what, String detailPrefix) {
        return indexOfNth(scenario, what, detailPrefix, true, 0);
    }

    /** Position of the first event of {@code scenario}/{@code what} whose detail contains {@code substring}, or -1. */
    public static int indexOfContaining(String scenario, String what, String substring) {
        return indexOfNth(scenario, what, substring, false, 0);
    }

    /** Position of the {@code n}-th (0-based) containing event of {@code scenario}/{@code what}, or -1. */
    public static int indexOfNthContaining(String scenario, String what, String substring, int n) {
        return indexOfNth(scenario, what, substring, false, n);
    }

    /**
     * Uptime of the {@code n}-th (0-based) event of {@code scenario}/{@code what}
     * whose detail contains {@code substring}, or -1: the restart ladder of rows
     * S13a/S13c is measured from these, not from the probe's own clock.
     */
    public static long uptimeOfNthContaining(String scenario, String what, String substring, int n) {
        int seen = 0;
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario) && e.what.equals(what) && e.detail.contains(substring)) {
                if (seen == n) {
                    return e.t;
                }
                seen++;
            }
        }
        return -1L;
    }

    /**
     * Position of the {@code n}-th (0-based) {@code scenario}/{@code what} event,
     * matching the detail by prefix or by containment. Positions count only that
     * scenario's events, so two lookups are directly comparable.
     */
    private static int indexOfNth(String scenario, String what, String needle, boolean prefix, int n) {
        int at = 0;
        int seen = 0;
        for (Entry e : snapshot()) {
            if (!e.scenario.equals(scenario)) {
                continue;
            }
            boolean match = e.what.equals(what)
                && (prefix ? e.detail.startsWith(needle) : e.detail.contains(needle));
            if (match) {
                if (seen == n) {
                    return at;
                }
                seen++;
            }
            at++;
        }
        return -1;
    }

    /** The last event of {@code scenario}, rendered: what a timeout verdict reports as observed. */
    public static String lastFor(String scenario) {
        Entry last = null;
        for (Entry e : snapshot()) {
            if (e.scenario.equals(scenario)) {
                last = e;
            }
        }
        return last == null ? "none" : last.render();
    }

    /** Simple class name of a (possibly nested) class name. */
    public static String simpleName(String className) {
        if (className == null) {
            return "null";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** Simple class name of a callback's component, for compact trace lines. */
    public static String shortComponent(ComponentName name) {
        return name == null ? "null" : simpleName(name.getClassName());
    }

    /** Identity hash rendered as hex: lets a trace show "same binder object" without a toString(). */
    public static String idHash(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }
}
