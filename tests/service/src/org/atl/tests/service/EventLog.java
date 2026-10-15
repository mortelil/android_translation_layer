/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B, LOCAL gate): ordered in-process event log.
 *
 * One static log records ordered (scenario, what, detail) events written by the
 * probe activity, by the probe's ServiceConnection callbacks and by the services
 * under test; the activity and its services share it in-process (package-private
 * access, never across processes - the isolated/remote components of S14 never
 * run, which is exactly what those rows assert).
 *
 * Every event is echoed immediately on stdout as a compact
 * `SVC <scenario> TRACE <what> [detail]` line so the run log carries the whole
 * trace as evidence; verdict and marker lines go through line() too, so all
 * output is one serialised, autoflushed stream (the watchdog thread and the
 * executor thread in S06a write concurrently).
 */
package org.atl.tests.service;

import android.content.ComponentName;
import java.util.ArrayList;

public final class EventLog {

    /** One ordered event: the acceptance row, the callback/step, and free-form evidence. */
    public static final class Entry {
        public final String scenario;
        public final String what;
        public final String detail;

        Entry(String scenario, String what, String detail) {
            this.scenario = scenario;
            this.what = what;
            this.detail = detail;
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
        Entry entry = new Entry(scenario, what, detail == null ? "" : detail);
        synchronized (lock) {
            entries.add(entry);
        }
        line("SVC " + scenario + " TRACE " + entry.render());
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

    /** Number of events of {@code scenario}/{@code what} whose detail contains {@code substring}. */
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
        ArrayList<Entry> all = snapshot();
        int at = 0;
        for (Entry e : all) {
            if (!e.scenario.equals(scenario)) {
                continue;
            }
            if (e.what.equals(what) && e.detail.startsWith(detailPrefix)) {
                return at;
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