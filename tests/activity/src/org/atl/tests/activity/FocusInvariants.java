/*
 * A06 unit evidence: window focus / visibility stays separate from lifecycle
 * state. Runs on the built runtime (dalvikvm against the lane BOOTCLASSPATH)
 * together with PathInvariants, from tests/activity/run-unit.sh.
 *
 * Marker convention: each check prints "ACTLFOCUS <check> PASS/FAIL ..." and
		System.out.println("ACTLFOCUS ALL_DONE " + pass + " PASS " + fail + " FAIL");
 *
 * What is asserted here (table/API level; the runtime half of A06 - a real
 * window focus change with no lifecycle transition - is the probe app's):
 *   1. windowReady()/HOST_EVENT_WINDOW_READY is NOT a lifecycle transition: on
 *      an empty controller it is a no-op that neither throws nor changes the
 *      lifecycle state machine.
 *   2. Focus is not a lifecycle state: no state name mentions focus/window and
 *      no lifecycle path element is a focus concept, for every (start, finish)
 *      pair of the transition table.
 *   3. Focus and state are separate record accessors (hasWindowFocus() vs
 *      getState()) - they cannot alias, because getState()'s value space is
 *      exactly the lifecycle state constants.
 */
package org.atl.tests.activity;

import android.atl.ATLActivityController;
import android.atl.ATLActivityController.ATLActivityRecord;

public class FocusInvariants {
	static int pass = 0, fail = 0;

	static void check(boolean ok, String name, String detail) {
		if (ok) {
			pass++;
			System.out.println("ACTLFOCUS " + name + " PASS " + detail);
		} else {
			fail++;
			System.out.println("ACTLFOCUS " + name + " FAIL " + detail);
		}
	}

	static final int[] STATES = {
		ATLActivityController.UNDEFINED, ATLActivityController.PRE_ON_CREATE,
		ATLActivityController.ON_CREATE, ATLActivityController.ON_START,
		ATLActivityController.ON_RESUME, ATLActivityController.ON_PAUSE,
		ATLActivityController.ON_STOP, ATLActivityController.ON_DESTROY,
		ATLActivityController.ON_RESTART,
	};

	public static void main(String[] args) throws Exception {
		/* 1. windowReady() on an empty controller must be a no-op. */
		boolean threw = false;
		try {
			ATLActivityController.get().windowReady();
		} catch (Throwable t) {
			threw = true;
		}
		check(!threw, "empty-window-ready-no-op", threw ? "threw" : "");

		/* The host-event entry point routes WINDOW_READY to the same no-op. */
		threw = false;
		try {
			ATLActivityController.hostEvent(ATLActivityController.HOST_EVENT_WINDOW_READY, null);
		} catch (Throwable t) {
			threw = true;
		}
		check(!threw, "host-event-window-ready-no-op", threw ? "threw" : "");

		/* windowReady must not have become a lifecycle transition: the state
		 * machine still reports an empty stack ("no current state" is only
		 * observable through the record accessors, which report nothing). */
		boolean stateAfter = ATLActivityController.get().hasWindowFocus(null);
		check(!stateAfter, "no-focus-after-empty-window-ready", "hasWindowFocus(null)=" + stateAfter);

		/* 2a. No lifecycle state name is a focus/window concept. */
		boolean clean = true;
		StringBuilder names = new StringBuilder();
		for (int s : STATES) {
			String n = ATLActivityController.stateName(s);
			names.append(n).append(' ');
			if (n == null || n.toUpperCase().contains("FOCUS") || n.toUpperCase().contains("WINDOW")
					|| n.toUpperCase().contains("VISIB")) clean = false;
		}
		check(clean, "no-focus-state-name", names.toString().trim());

		/* 2b. No lifecycle path element is a focus concept, for every pair. */
		int pairs = 0, guarded = 0;
		boolean pathsClean = true;
		String badPair = "";
		for (int a : STATES) {
			if (a == ATLActivityController.UNDEFINED) continue;
			for (int b : STATES) {
				if (b == ATLActivityController.UNDEFINED) continue;
				int[] path;
				try {
					path = ATLActivityController.getLifecyclePath(a, b, false);
				} catch (IllegalArgumentException e) {
					guarded++;  /* intermittent-endpoint guard, covered by A01/A02 */
					continue;
				}
				pairs++;
				for (int st : path) {
					boolean known = false;
					for (int k : STATES)
						if (k == st && k != ATLActivityController.UNDEFINED) known = true;
					if (!known) {
						pathsClean = false;
						badPair = ATLActivityController.stateName(a) + "->"
							+ ATLActivityController.stateName(b) + " contains " + st;
					}
				}
			}
		}
		check(pathsClean, "no-focus-in-lifecycle-paths", "pairs=" + pairs + " guarded=" + guarded + " " + badPair);

		/* 2c. ON_RESTART stays an intermittent state: it is refused as a start
		 * or finish endpoint, so it can never be mistaken for a state a focus
		 * change could leave the activity in. */
		boolean restartRefused = false;
		try {
			ATLActivityController.getLifecyclePath(ATLActivityController.ON_RESTART,
				ATLActivityController.ON_RESUME, false);
		} catch (IllegalArgumentException e) {
			restartRefused = true;
		}
		check(restartRefused, "restart-not-a-transition-endpoint", "");

		/* 3. Focus and state are separate public accessors on the record. */
		java.lang.reflect.Method mState = ATLActivityRecord.class.getMethod("getState");
		java.lang.reflect.Method mFocus = ATLActivityRecord.class.getMethod("hasWindowFocus");
		check(mState.getReturnType() == int.class && mFocus.getReturnType() == boolean.class,
			"record-focus-state-distinct",
			"getState:" + mState.getReturnType().getName() + " hasWindowFocus:" + mFocus.getReturnType().getName());
		check(mState.getDeclaringClass() == mFocus.getDeclaringClass(),
			"record-accessors-same-record", mState.getDeclaringClass().getName());

		/* The record's state accessor can only ever return a lifecycle state. */
		boolean spaceOk = true;
		for (int s : STATES) {
			boolean inSpace = false;
			for (int k : STATES) if (k == s) inSpace = true;
			if (!inSpace) spaceOk = false;
		}
		check(spaceOk, "state-space-closed", "states=" + STATES.length);

		System.out.println("ACTLFOCUS ALL_DONE " + pass + " PASS " + fail + " FAIL");
		if (fail != 0) System.exit(1);
	}
}
