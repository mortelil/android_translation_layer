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
 *
 * CROSS-PROCESS VIEW (realhost arm): with ATL_SVC_TRACE_DIR set, every process
 * (the loader and every spawned service child) also appends each line to
 * `<pid>.log` in that directory, and a row's queries see the merged view: this
 * process' own entries first, in recording order, then the entries read out of
 * the other processes' files. Foreign entries are appended in the order they are
 * DISCOVERED (per refresh: file name order, then line order inside a file), so
 * the order inside one process is preserved while no cross-process ordering is
 * claimed - a row that needs ordering across processes has to assert it against
 * the events' own t= uptimes, not against the position in the merged list.
 * The loader's own file is never read (its events are already in memory), and
 * `<pid>.log` name matching keeps unrelated files out of the view. Without the
 * env var there is no file I/O at all and the view is exactly this process'
 * entries, byte for byte as before.
 */
package org.atl.tests.service;

import android.content.ComponentName;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

public final class EventLog {

    /** One ordered event: the acceptance row, the callback/step, and free-form evidence. */
    public static final class Entry {
        public final String scenario;
        public final String what;
        public final String detail;
        /** SystemClock.uptimeMillis() when the event was recorded: what rows S13a/S13c assert against. */
        public final long t;
        /**
         * The process that recorded the event: {@link #SELF_PID} for this process'
         * own entries, the trace file's pid for a foreign one. It is NOT rendered -
         * the frozen TRACE line format has no pid column - it only tells the local
         * and the foreign half of the merged view apart.
         */
        public final int pid;

        Entry(String scenario, String what, String detail, long t, int pid) {
            this.scenario = scenario;
            this.what = what;
            this.detail = detail;
            this.t = t;
            this.pid = pid;
        }

        public String render() {
            return detail.length() == 0 ? what : what + " " + detail;
        }
    }

    /** The per-process trace directory a run configures (see tests/service/run.sh). */
    public static final String TRACE_DIR_ENV = "ATL_SVC_TRACE_DIR";
    /** This process' pid: the pid of our own entries, and the name of our own trace file. */
    private static final int SELF_PID = android.os.Process.myPid();
    /**
     * The trace directory of {@link #TRACE_DIR_ENV}, or null when it is unset,
     * empty or not an existing directory - then this class does no file I/O at all
     * and behaves exactly as it did before the cross-process view existed.
     */
    private static final File traceDir = resolveTraceDir();

    private static final ArrayList<Entry> entries = new ArrayList<Entry>();
    private static final Object lock = new Object();

    /** Foreign trace files: absolute path -> bytes already consumed (always at a line end). */
    private static final HashMap<String, Long> foreignOffsets = new HashMap<String, Long>();
    /** The TRACE entries read out of the other processes' trace files, in discovery order. */
    private static final ArrayList<Entry> foreign = new ArrayList<Entry>();
    /** Paths whose read already failed: one stderr line per path, not one per refresh. */
    private static final HashSet<String> reportedForeignErrors = new HashSet<String>();
    /** Set by the first failed append: one stderr line per process, then no more file writes. */
    private static boolean appendFailed;

    private EventLog() {
    }

    /** The configured trace directory, or null when this process does not trace to a file. */
    private static File resolveTraceDir() {
        String path = System.getenv(TRACE_DIR_ENV);
        if (path == null || path.length() == 0) {
            return null;
        }
        File dir = new File(path);
        return dir.isDirectory() ? dir : null;
    }

    /** Records one event and echoes it as a TRACE line (autoflushed). */
    public static void event(String scenario, String what, String detail) {
        Entry entry = new Entry(scenario, what, detail == null ? "" : detail,
            SystemClock.uptimeMillis(), SELF_PID);
        synchronized (lock) {
            entries.add(entry);
        }
        line("SVC " + scenario + " TRACE t=" + entry.t + " " + entry.render());
    }

    /**
     * Writes one whole stdout line; serialised so concurrent writers cannot interleave.
     * With a trace directory configured the line is also appended to this process'
     * own `<pid>.log`, which is how the loader reads a spawned child's events.
     */
    public static void line(String text) {
        synchronized (lock) {
            System.out.println(text);
            System.out.flush();
            appendToTrace(text);
        }
    }

    /**
     * Appends one line to this process' trace file (UTF-8, append + flush + close:
     * a few hundred short lines per run make anything smarter pointless).
     * Every line is appended, not only TRACE lines: {@link #parseForeign} ignores
     * what it cannot use, and the sink stays a plain tee of stdout.
     */
    private static void appendToTrace(String text) {
        if (traceDir == null || appendFailed) {
            return;
        }
        File file = new File(traceDir, SELF_PID + ".log");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, true);
            out.write((text + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            appendFailed = true;
            System.err.println("EventLog: cannot append to " + file.getAbsolutePath() + ": "
                + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the line is already written or already reported
                }
            }
        }
    }

    /**
     * The merged view: this process' own entries in recording order, then the
     * foreign entries discovered so far (see the class comment for the ordering
     * rule). Every foreign file is re-read first, so a query sees the events a
     * spawned child appended up to now.
     */
    private static ArrayList<Entry> snapshot() {
        synchronized (lock) {
            refreshForeign();
            ArrayList<Entry> merged = new ArrayList<Entry>(entries.size() + foreign.size());
            merged.addAll(entries);
            merged.addAll(foreign);
            return merged;
        }
    }

    /**
     * Appends every complete TRACE line the other processes' trace files gained
     * since the last call. This process' own file is never read: its events are
     * already in {@link #entries}, and reading it would count them twice.
     */
    private static void refreshForeign() {
        if (traceDir == null) {
            return;
        }
        String[] names = traceDir.list();
        if (names == null) {
            return;
        }
        // Sorted, so the discovery order does not depend on the filesystem's.
        Arrays.sort(names);
        for (String name : names) {
            int pid = pidOfLogName(name);
            if (pid < 0 || pid == SELF_PID) {
                continue;
            }
            readForeignFile(new File(traceDir, name), pid);
        }
    }

    /** The pid of a `<digits>.log` name, or -1 when the name is not one. */
    private static int pidOfLogName(String name) {
        if (!name.endsWith(".log") || name.length() <= 4) {
            return -1;
        }
        String digits = name.substring(0, name.length() - 4);
        for (int i = 0; i < digits.length(); i++) {
            if (!isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Consumes the bytes a foreign trace file gained since the stored offset.
     * Only newline-terminated lines are accepted: a file whose writer is between
     * the write and the flush of a line leaves that line's bytes unconsumed, so a
     * line can never be counted half-read or twice.
     */
    private static void readForeignFile(File file, int pid) {
        String path = file.getAbsolutePath();
        Long stored = foreignOffsets.get(path);
        long offset = stored == null ? 0L : stored.longValue();
        byte[] data;
        try {
            long length = file.length();
            if (length <= offset) {
                return;
            }
            data = new byte[(int) (length - offset)];
            RandomAccessFile in = new RandomAccessFile(file, "r");
            try {
                in.seek(offset);
                in.readFully(data);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            reportForeignError("cannot read", path, e);
            return;
        }
        ArrayList<Entry> accepted = new ArrayList<Entry>();
        try {
            int start = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] != '\n') {
                    continue;
                }
                Entry entry = parseForeign(new String(data, start, i - start, "UTF-8"), pid);
                if (entry != null) {
                    accepted.add(entry);
                }
                offset += (i - start) + 1;
                start = i + 1;
            }
        } catch (IOException e) {
            // "UTF-8" always exists, so this cannot happen; a file that cannot be
            // decoded must not advance its offset and lose the line.
            reportForeignError("cannot decode", path, e);
            return;
        }
        foreign.addAll(accepted);
        foreignOffsets.put(path, Long.valueOf(offset));
    }

    private static void reportForeignError(String what, String path, IOException e) {
        if (reportedForeignErrors.add(path)) {
            System.err.println("EventLog: " + what + " " + path + ": " + e.getMessage());
        }
    }

    /**
     * One foreign line as an Entry, or null when the line is not a TRACE line:
     * `SVC <scenario> TRACE t=<digits> <what>[ <detail>]`. Non-TRACE lines are
     * ignored on purpose, which is why the sink appends every stdout line.
     */
    private static Entry parseForeign(String line, int pid) {
        if (!line.startsWith("SVC ")) {
            return null;
        }
        int traceAt = line.indexOf(" TRACE ", 4);
        if (traceAt < 0) {
            return null;
        }
        String scenario = line.substring(4, traceAt);
        int at = traceAt + " TRACE ".length();
        if (!line.startsWith("t=", at)) {
            return null;
        }
        int digitsFrom = at + 2;
        int digitsTo = digitsFrom;
        while (digitsTo < line.length() && isDigit(line.charAt(digitsTo))) {
            digitsTo++;
        }
        if (digitsTo == digitsFrom || digitsTo >= line.length() || line.charAt(digitsTo) != ' ') {
            return null;
        }
        long t;
        try {
            t = Long.parseLong(line.substring(digitsFrom, digitsTo));
        } catch (NumberFormatException e) {
            return null;
        }
        int whatFrom = digitsTo + 1;
        int whatTo = line.indexOf(' ', whatFrom);
        if (whatTo < 0) {
            return new Entry(scenario, line.substring(whatFrom), "", t, pid);
        }
        return new Entry(scenario, line.substring(whatFrom, whatTo),
            line.substring(whatTo + 1), t, pid);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * This process' pid. A realhost row proves a spawned child served the
     * component by showing a foreign pid different from this one.
     */
    public static int pid() {
        return SELF_PID;
    }

    /** Number of events of {@code scenario}/{@code prefix} THIS process recorded. */
    public static int countLocal(String scenario, String prefix) {
        int n = 0;
        for (Entry e : snapshot()) {
            if (e.pid == SELF_PID && e.scenario.equals(scenario) && e.what.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** Number of events of {@code scenario}/{@code prefix} a spawned child's trace file recorded. */
    public static int countForeign(String scenario, String prefix) {
        int n = 0;
        for (Entry e : snapshot()) {
            if (e.pid != SELF_PID && e.scenario.equals(scenario) && e.what.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** The pid of the first foreign process that recorded {@code scenario}/{@code prefix}, or -1. */
    public static int foreignPid(String scenario, String prefix) {
        for (Entry e : snapshot()) {
            if (e.pid != SELF_PID && e.scenario.equals(scenario) && e.what.startsWith(prefix)) {
                return e.pid;
            }
        }
        return -1;
    }

    /** Every distinct foreign pid that recorded {@code scenario}/{@code prefix}, ascending. */
    public static int[] foreignPids(String scenario, String prefix) {
        ArrayList<Integer> pids = new ArrayList<Integer>();
        for (Entry e : snapshot()) {
            if (e.pid == SELF_PID || !e.scenario.equals(scenario) || !e.what.startsWith(prefix)) {
                continue;
            }
            Integer pid = Integer.valueOf(e.pid);
            if (!pids.contains(pid)) {
                pids.add(pid);
            }
        }
        Collections.sort(pids);
        int[] out = new int[pids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = pids.get(i).intValue();
        }
        return out;
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
