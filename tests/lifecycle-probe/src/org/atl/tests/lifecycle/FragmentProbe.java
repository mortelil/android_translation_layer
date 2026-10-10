/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;

/**
 * FT scenario matrix against the framework {@code android.app.FragmentManager}
 * (not androidx). Every operation and its observed result is logged as one
 * {@code FragMgr} event so the analyzer can compare exact tags/lookups rather
 * than booleans. Fragment identity is carried in a private subclass field
 * (marker int), never a raw address and never a Bundle argument
 * ({@code Fragment.setArguments} is not implemented by ATL).
 *
 * Runs synchronously on the main thread inside a resumed Activity; all
 * transaction commits are followed by {@code executePendingTransactions()} so
 * the lookup observes the applied state. Every op is wrapped so a missing ATL
 * primitive is recorded as an event and the remaining scenarios still run.
 */
final class FragmentProbe {

    private FragmentProbe() {
    }

    /**
     * Marker-carrying Fragment; identity travels in the object, not a Bundle.
     * MUST be a public static nested class: {@code BackStackRecord.doAddOp}
     * rejects anonymous / non-public / non-static fragment classes with
     * {@code IllegalStateException} because they cannot be recreated from
     * instance state. The public no-arg constructor is what that recreation
     * path uses; the marker is only set on the live instance we add.
     */
    public static final class MarkedFragment extends Fragment {
        final int markerId;

        public MarkedFragment() {
            this.markerId = 0;
        }

        MarkedFragment(int markerId) {
            this.markerId = markerId;
        }
    }

    private static Fragment marked(int id) {
        return new MarkedFragment(id);
    }

    private static String marker(Fragment f) {
        if (f == null) {
            return "null";
        }
        if (!(f instanceof MarkedFragment)) {
            return "frag#?";
        }
        return "frag#" + ((MarkedFragment) f).markerId;
    }

    private static String tagOf(Fragment f) {
        return f == null ? "null" : String.valueOf(f.getTag());
    }

    private static void ev(Trace t, String event) {
        t.event("FragMgr", event, "-", "-", "-");
    }

    /** Record why an FT op failed (class, message and top frame), never a bare code. */
    private static void evError(Trace t, Throwable e) {
        StackTraceElement[] st = e.getStackTrace();
        String at = (st != null && st.length > 0) ? (" at " + st[0]) : "";
        String msg = e.getMessage() == null ? "" : (": " + e.getMessage());
        ev(t, "exception=" + e.getClass().getName() + msg + at);
    }

    static void run(Trace t, Activity host) {
        FragmentManager fm = host.getFragmentManager();

        // F-ADD-TAG: tagged add -> commit -> findFragmentByTag
        t.scenBegin("F-ADD-TAG");
        Fragment f1 = marked(1);
        try {
            FragmentTransaction tx = fm.beginTransaction();
            tx.add(f1, "t1");
            ev(t, "add:tag=t1");
            tx.commit();
            ev(t, "commit");
            fm.executePendingTransactions();
            ev(t, "executePendingTransactions");
            ev(t, "find:t1=" + marker(fm.findFragmentByTag("t1")));
        } catch (Throwable e) {
            evError(t, e);
        }
        t.scenEnd("F-ADD-TAG");

        // F-TAG-READ: the tag is readable on the added fragment ("tag after add")
        t.scenBegin("F-TAG-READ");
        try {
            ev(t, "getTag:t1=" + tagOf(fm.findFragmentByTag("t1")));
        } catch (Throwable e) {
            evError(t, e);
        }
        t.scenEnd("F-TAG-READ");

        // F-DUP-TAG: two added fragments with the same tag; newest-added wins
        t.scenBegin("F-DUP-TAG");
        try {
            Fragment fa = marked(2);
            Fragment fb = marked(3);
            FragmentTransaction tx = fm.beginTransaction();
            tx.add(fa, "tdup");
            ev(t, "add:tag=tdup");
            tx.add(fb, "tdup");
            ev(t, "add:tag=tdup");
            tx.commit();
            ev(t, "commit");
            fm.executePendingTransactions();
            ev(t, "executePendingTransactions");
            ev(t, "find:tdup=" + marker(fm.findFragmentByTag("tdup")));
        } catch (Throwable e) {
            evError(t, e);
        }
        t.scenEnd("F-DUP-TAG");

        // F-NULL-MISS: null tag and unknown tag both return null
        t.scenBegin("F-NULL-MISS");
        try {
            ev(t, "find:null=" + marker(fm.findFragmentByTag(null)));
            ev(t, "find:missing=" + marker(fm.findFragmentByTag("nope")));
        } catch (Throwable e) {
            evError(t, e);
        }
        t.scenEnd("F-NULL-MISS");

        // F-PRE-COMMIT: lookup before vs after the transaction is applied
        t.scenBegin("F-PRE-COMMIT");
        try {
            Fragment fp = marked(4);
            FragmentTransaction tx = fm.beginTransaction();
            tx.add(fp, "tpre");
            ev(t, "add:tag=tpre");
            ev(t, "find:tpre=" + marker(fm.findFragmentByTag("tpre")));
            tx.commit();
            ev(t, "commit");
            fm.executePendingTransactions();
            ev(t, "executePendingTransactions");
            ev(t, "find:tpre=" + marker(fm.findFragmentByTag("tpre")));
        } catch (Throwable e) {
            evError(t, e);
        }
        t.scenEnd("F-PRE-COMMIT");

        // F-REMOVE: remove the t1 fragment, then look it up again
        t.scenBegin("F-REMOVE");
        try {
            Fragment r = fm.findFragmentByTag("t1");
            ev(t, "find:t1=" + marker(r));
            FragmentTransaction tx = fm.beginTransaction();
            tx.remove(r);
            ev(t, "remove:t1");
            tx.commit();
            ev(t, "commit");
            fm.executePendingTransactions();
            ev(t, "executePendingTransactions");
            ev(t, "find:t1=" + marker(fm.findFragmentByTag("t1")));
        } catch (Throwable e) {
            evError(t, e);
        }
        t.scenEnd("F-REMOVE");
    }
}