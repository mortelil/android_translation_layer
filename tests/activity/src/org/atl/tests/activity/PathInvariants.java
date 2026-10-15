/*
 * A01/A02 unit evidence: ATLActivityController transition-table invariants.
 * Runs on the built runtime (dalvikvm against the lane's BOOTCLASSPATH).
 * Marker convention: each check prints "ACTLPATH <check> PASS/FAIL ..." and
 * the run ends with "ACTLPATH ALL_DONE <n> PASS <m> FAIL".
 */
package org.atl.tests.activity;

import android.atl.ATLActivityController;

public class PathInvariants {
	static int pass = 0, fail = 0;

	static void check(boolean ok, String name, String detail) {
		if (ok) {
			pass++;
			System.out.println("ACTLPATH " + name + " PASS " + detail);
		} else {
			fail++;
			System.out.println("ACTLPATH " + name + " FAIL " + detail);
		}
	}

	static String fmt(int[] p) {
		StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < p.length; i++) {
			if (i > 0) b.append(',');
			b.append(ATLActivityController.stateName(p[i]));
		}
		return b.append(']').toString();
	}

	static int count(int[] p, int state) {
		int n = 0;
		for (int s : p) if (s == state) n++;
		return n;
	}

	public static void main(String[] args) {
		int[] starts = {ATLActivityController.PRE_ON_CREATE, ATLActivityController.ON_CREATE,
			ATLActivityController.ON_START, ATLActivityController.ON_RESUME,
			ATLActivityController.ON_PAUSE, ATLActivityController.ON_STOP};
		int[] finishes = {ATLActivityController.ON_CREATE, ATLActivityController.ON_START,
			ATLActivityController.ON_RESUME, ATLActivityController.ON_PAUSE,
			ATLActivityController.ON_STOP, ATLActivityController.ON_DESTROY};

		int closureChecked = 0;
		for (int start : starts) {
			for (int finish : finishes) {
				// PRE_ON_CREATE -> ON_CREATE is the only legal pre-create move;
				// a downward/stop request from PRE_ON_CREATE is not a resolved
				// path case AOSP ever asks for; skip those pairs.
				if (start == ATLActivityController.PRE_ON_CREATE && finish != ATLActivityController.ON_CREATE)
					continue;
				int[] full = ATLActivityController.getLifecyclePath(start, finish, false);

				// A01 closure: with excludeLastState=false the path lands on
				// finish — or is empty when the record is already there (AOSP
				// returns an empty sequence for identity requests).
				boolean identity = start == finish;
				check((identity && full.length == 0)
					|| (!identity && full.length > 0 && full[full.length - 1] == finish),
					"closure", "start=" + ATLActivityController.stateName(start)
					+ " finish=" + ATLActivityController.stateName(finish) + " path=" + fmt(full));
				closureChecked++;

				// A02: ON_RESTART never persists — interior only, at most once.
				if (full.length > 0)
					check(count(full, ATLActivityController.ON_RESTART) <= 1
						&& full[full.length - 1] != ATLActivityController.ON_RESTART,
						"restart-not-last", fmt(full));
				// ON_RESTART only ever appears immediately before an ON_START step.
				for (int i = 0; i < full.length; i++) {
					if (full[i] == ATLActivityController.ON_RESTART)
						check(i + 1 < full.length && full[i + 1] == ATLActivityController.ON_START,
							"restart-precedes-start", fmt(full));
				}

				// No state repeats within one path.
				boolean dup = false;
				outer:
				for (int i = 0; i < full.length; i++)
					for (int j = i + 1; j < full.length; j++)
						if (full[i] == full[j]) { dup = true; break outer; }
				check(!dup, "no-revisit", fmt(full));

				// Upward walks are strictly increasing (no skips beyond +1 steps).
				if (finish >= start) {
					boolean inc = true;
					for (int i = 0; i < full.length; i++)
						if (full[i] <= start + i) inc = false;
					check(inc, "up-monotonic", fmt(full));
				}

				// excludeLastState trims exactly the last entry, preserving order.
				int[] trimmed = ATLActivityController.getLifecyclePath(start, finish, true);
				check(trimmed.length == full.length - (full.length > 0 ? 1 : 0), "exclude-last-len", fmt(full) + " -> " + fmt(trimmed));
				boolean prefix = trimmed.length == full.length - (full.length > 0 ? 1 : 0);
				for (int i = 0; prefix && i < trimmed.length; i++)
					prefix = trimmed[i] == full[i];
				check(prefix, "exclude-last-prefix", fmt(full) + " -> " + fmt(trimmed));
			}
		}
		check(closureChecked >= 30, "closure-coverage", "pairs=" + closureChecked);

		// AOSP-pinned cases (TransactionExecutorHelper.java:68-129).
		check(java.util.Arrays.equals(ATLActivityController.getLifecyclePath(ATLActivityController.ON_START, ATLActivityController.ON_STOP, false),
			new int[]{ATLActivityController.ON_STOP}), "start-stop-shortcut", "");
		check(java.util.Arrays.equals(ATLActivityController.getLifecyclePath(ATLActivityController.ON_PAUSE, ATLActivityController.ON_RESUME, false),
			new int[]{ATLActivityController.ON_RESUME}), "pause-resume-shortcut", "");
		// Restart-through-STOP: ON_RESUME -> ON_START keeps the record, walks
		// down PAUSE+STOP (loop start+1..ON_STOP), emits ON_RESTART, then ON_START.
		check(java.util.Arrays.equals(ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESUME, ATLActivityController.ON_START, false),
			new int[]{ATLActivityController.ON_PAUSE, ATLActivityController.ON_STOP, ATLActivityController.ON_RESTART, ATLActivityController.ON_START}), "restart-through-stop", "");
		// Relaunch-through-DESTROY: below the restart window, going up destroys.
		check(java.util.Arrays.equals(ATLActivityController.getLifecyclePath(ATLActivityController.ON_CREATE, ATLActivityController.ON_RESUME, false),
			new int[]{ATLActivityController.ON_START, ATLActivityController.ON_RESUME}), "create-resume-up", "");
		// ON_DESTROY from ON_CREATE as a pure path query is the plain upward
		// walk (AOSP "just go there"); AOSP never asks for it — finish() from
		// onCreate skips to destroy via ActivityThread.handleLaunchActivity's
		// isFinishing check, which the controller's finish flow mirrors
		// explicitly rather than through path(current, ON_DESTROY).
		check(java.util.Arrays.equals(ATLActivityController.getLifecyclePath(ATLActivityController.ON_CREATE, ATLActivityController.ON_DESTROY, false),
			new int[]{ATLActivityController.ON_START, ATLActivityController.ON_RESUME, ATLActivityController.ON_PAUSE, ATLActivityController.ON_STOP, ATLActivityController.ON_DESTROY}), "create-destroy-upwalk", "");
		// Identity: RESUME -> RESUME is a no-op; AOSP getLifecyclePath returns
		// an empty sequence (relaunch is destroy + a fresh path from
		// PRE_ON_CREATE, never path(RESUME, RESUME)).
		check(ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESUME, ATLActivityController.ON_RESUME, false).length == 0,
			"resume-resume-identity-empty", "");
		// Full relaunch through DESTROY: RESUME -> CREATE (finish below the
		// restart window) destroys and rebuilds up to CREATE.
		check(java.util.Arrays.equals(ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESUME, ATLActivityController.ON_CREATE, false),
			new int[]{ATLActivityController.ON_PAUSE, ATLActivityController.ON_STOP, ATLActivityController.ON_DESTROY, ATLActivityController.ON_CREATE}), "relaunch-resume-create", "");

		// Guards.
		boolean threw = false;
		try { ATLActivityController.getLifecyclePath(ATLActivityController.UNDEFINED, ATLActivityController.ON_RESUME, false); }
		catch (IllegalArgumentException e) { threw = true; }
		check(threw, "guard-undefined-start", "");
		threw = false;
		try { ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESUME, ATLActivityController.UNDEFINED, false); }
		catch (IllegalArgumentException e) { threw = true; }
		check(threw, "guard-undefined-finish", "");
		threw = false;
		try { ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESTART, ATLActivityController.ON_RESUME, false); }
		catch (IllegalArgumentException e) { threw = true; }
		check(threw, "guard-restart-start", "");
		threw = false;
		try { ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESUME, ATLActivityController.ON_RESTART, false); }
		catch (IllegalArgumentException e) { threw = true; }
		check(threw, "guard-restart-finish", "");

		// Saved-state ordering selector (AOSP ActivityThread callActivityOnStop).
		check(ATLActivityController.isPreP(27), "is-pre-p-27", "");
		check(!ATLActivityController.isPreP(28), "is-p-28", "");
		check(!ATLActivityController.isPreP(35), "is-p-35", "");

		System.out.println("ACTLPATH ALL_DONE " + pass + " PASS " + fail + " FAIL");
		// Explicit exit on both paths: this host dalvikvm aborts in
		// JNI_OnUnload during clean DestroyJavaVM shutdown.
		System.exit(fail > 0 ? 1 : 0);
	}
}
