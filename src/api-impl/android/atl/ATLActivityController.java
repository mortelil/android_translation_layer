/*
 * ATLActivityController — authoritative activity lifecycle owner for ATL
 * (lifecycle-foundation lane C, architecture.md §6).
 *
 * Transition engine ADAPTED (algorithm copied verbatim) from AOSP
 * TransactionExecutorHelper.getLifecyclePath, tag android-17.0.0_r1,
 * commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
 * core/java/android/app/servertransaction/TransactionExecutorHelper.java:68-129.
 * State ladder values from
 * core/java/android/app/servertransaction/ActivityLifecycleItem.java:46-55.
 * Perform* step mapping mirrors
 * core/java/android/app/servertransaction/TransactionExecutor.java
 * performLifecycleSequence:198-243 (ON_RESTART executes performRestart with
 * start=false; ON_START then executes performStart as its own path step).
 *
 * Copyright (C) 2017 The Android Open Source Project (algorithm)
 * ATL adaptation Copyright (C) 2026 the ATL contributors (GPL-3.0-only,
 * same license as the rest of this tree).
 */
package android.atl;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * The single owner of activity lifecycle state for a launcher session.
 * Desired state lives ONLY in the controller's records; host events (GTK
 * back button, window close-request, window map/focus, finish/recreate/new
 * intent) arrive through one seam and become path requests resolved by the
 * AOSP transition engine. Visibility/focus are tracked separately from the
 * lifecycle ladder (A06): focus changes never mutate lifecycle state.
 *
 * Host-event entry points (native seam, to be wired in the GTK/JNI seam
 * change — see tests/activity/DESIGN-CHECKPOINT.md):
 *   HOST_EVENT_START / HOST_EVENT_BACK / HOST_EVENT_WINDOW_CLOSE_REQUEST /
 *   HOST_EVENT_WINDOW_READY / HOST_EVENT_NEW_INTENT.
 */
public final class ATLActivityController {
	private static final String TAG = "ATLActivityController";

	/** Lifecycle ladder, values identical to AOSP ActivityLifecycleItem. */
	public static final int UNDEFINED = -1;
	public static final int PRE_ON_CREATE = 0;
	public static final int ON_CREATE = 1;
	public static final int ON_START = 2;
	public static final int ON_RESUME = 3;
	public static final int ON_PAUSE = 4;
	public static final int ON_STOP = 5;
	public static final int ON_DESTROY = 6;
	/** Never a resting state: it only appears inside a resolved path (A02). */
	public static final int ON_RESTART = 7;

	/** Native seam event codes; mirrored in android_app_Activity.c. */
	public static final int HOST_EVENT_START = 1;
	public static final int HOST_EVENT_BACK = 2;
	public static final int HOST_EVENT_WINDOW_CLOSE_REQUEST = 3;
	public static final int HOST_EVENT_WINDOW_READY = 4;
	public static final int HOST_EVENT_NEW_INTENT = 5;

	public static String stateName(int state) {
		switch (state) {
			case UNDEFINED: return "UNDEFINED";
			case PRE_ON_CREATE: return "PRE_ON_CREATE";
			case ON_CREATE: return "ON_CREATE";
			case ON_START: return "ON_START";
			case ON_RESUME: return "ON_RESUME";
			case ON_PAUSE: return "ON_PAUSE";
			case ON_STOP: return "ON_STOP";
			case ON_DESTROY: return "ON_DESTROY";
			case ON_RESTART: return "ON_RESTART";
			default: return "INVALID(" + state + ")";
		}
	}

	/** One activity: the current instance plus its lifecycle bookkeeping. */
	public static final class ATLActivityRecord {
		public final Activity activity;
		public final android.content.Intent intent;
		/** Generation of the *instance*; bumped on every recreate (A08). */
		public int generation = 1;
		/** Current resting lifecycle state on the ladder (never ON_RESTART). */
		int state = PRE_ON_CREATE;
		/** Saved instance state produced by a relaunch, restored on the way up. */
		Bundle savedState;
		/** finish() requested; teardown posts exactly once (A05). */
		boolean finishing;
		/** recreate() in flight on this record; suppresses re-entry (A05). */
		boolean relaunching;
		/** Window focus is NOT lifecycle state (A06). */
		boolean hasWindowFocus;

		ATLActivityRecord(Activity activity, android.content.Intent intent) {
			this.activity = activity;
			this.intent = intent;
		}

		public int getState() { return state; }
		public boolean isFinishing() { return finishing; }
		public boolean hasWindowFocus() { return hasWindowFocus; }
	}

	private static volatile ATLActivityController instance;

	/** Back stack, index 0 = top/current (mirrors the native backlog order). */
	private final ArrayList<ATLActivityRecord> records = new ArrayList<>();

	public static ATLActivityController get() {
		ATLActivityController c = instance;
		if (c == null) {
			synchronized (ATLActivityController.class) {
				c = instance;
				if (c == null)
					instance = c = new ATLActivityController();
			}
		}
		return c;
	}

	private ATLActivityController() {}

	public List<ATLActivityRecord> getRecords() { return new ArrayList<>(records); }
	public ATLActivityRecord recordFor(Activity activity) {
		for (ATLActivityRecord r : records)
			if (r.activity == activity)
				return r;
		return null;
	}

	/** Registers a freshly constructed activity at the top of the stack. */
	public ATLActivityRecord attach(Activity activity, android.content.Intent intent) {
		ATLActivityRecord existing = recordFor(activity);
		if (existing != null)
			throw new IllegalStateException("activity attached twice: " + activity);
		ATLActivityRecord r = new ATLActivityRecord(activity, intent);
		records.add(0, r);
		return r;
	}

	void detach(ATLActivityRecord r) { records.remove(r); }

	/**
	 * Lifecycle path resolution. Copied from AOSP
	 * TransactionExecutorHelper.getLifecyclePath (android-17.0.0_r1,
	 * 94b4c163b7df, TransactionExecutorHelper.java:68-129) with identical
	 * rules: upward walk; START->STOP shortcut; downward walk with
	 * PAUSE->RESUME shortcut, restart-through-STOP when the window
	 * [ON_START, ON_STOP] overlaps, relaunch-through-DESTROY otherwise;
	 * excludeLastState trims the final entry; UNDEFINED endpoints and
	 * ON_RESTART endpoints are rejected.
	 *
	 * @return states to execute in order, starting with the state FOLLOWING
	 *         {@code start}; never contains a resting ON_RESTART at the end.
	 */
	public static int[] getLifecyclePath(int start, int finish, boolean excludeLastState) {
		if (start == UNDEFINED || finish == UNDEFINED)
			throw new IllegalArgumentException("Can't resolve lifecycle path for undefined state");
		if (start == ON_RESTART || finish == ON_RESTART)
			throw new IllegalArgumentException("Can't start or finish in intermittent RESTART state");
		if (finish == PRE_ON_CREATE && start != finish)
			throw new IllegalArgumentException("Can only start in pre-onCreate state");

		ArrayList<Integer> seq = new ArrayList<>(6);
		if (finish >= start) {
			if (start == ON_START && finish == ON_STOP) {
				// A case when we go from start to stop state soon, we don't need
				// to go through the resumed, paused state.
				seq.add(ON_STOP);
			} else {
				// just go there
				for (int i = start + 1; i <= finish; i++)
					seq.add(i);
			}
		} else { // finish < start, can't just cycle down
			if (start == ON_PAUSE && finish == ON_RESUME) {
				// Special case when we can just directly go to resumed state.
				seq.add(ON_RESUME);
			} else if (start <= ON_STOP && finish >= ON_START) {
				// Restart and go to required state: go to stopped state first,
				// restart, then go up.
				for (int i = start + 1; i <= ON_STOP; i++)
					seq.add(i);
				seq.add(ON_RESTART);
				for (int i = ON_START; i <= finish; i++)
					seq.add(i);
			} else {
				// Relaunch and go to required state: go to destroyed state
				// first, then from created up to the required state.
				for (int i = start + 1; i <= ON_DESTROY; i++)
					seq.add(i);
				for (int i = ON_CREATE; i <= finish; i++)
					seq.add(i);
			}
		}

		if (excludeLastState && !seq.isEmpty())
			seq.remove(seq.size() - 1);

		int[] path = new int[seq.size()];
		for (int i = 0; i < path.length; i++)
			path[i] = seq.get(i);
		return path;
	}

	/**
	 * Executes one path step against the record's current instance, following
	 * the AOSP TransactionExecutor.performLifecycleSequence state-to-handler
	 * mapping. ON_RESUME pairs performResume with onPostResume (AOSP keeps
	 * onPostResume inside the resume step of the launch/relaunch flow).
	 * Lifecycle state is updated only after the callback returns.
	 */
	void performState(ATLActivityRecord r, int state, Bundle launchSavedState) {
		Activity a = r.activity;
		switch (state) {
			case ON_CREATE:
				a.performCreate(launchSavedState);
				break;
			case ON_START:
				a.performStart();
				break;
			case ON_RESTART:
				// AOSP: performRestartActivity(r, start=false) — onRestart only;
				// a following ON_START path step performs the actual start.
				if (r.state >= ON_STOP || r.state == ON_PAUSE)
					a.performRestart();
				break;
			case ON_RESUME:
				a.performResume();
				a.onPostResume();
				break;
			case ON_PAUSE:
				a.performPause();
				break;
			case ON_STOP:
				a.performStop();
				break;
			case ON_DESTROY:
				a.performDestroy();
				break;
			default:
				throw new IllegalArgumentException("Unexpected lifecycle state: " + stateName(state));
		}
		r.state = state;
	}

	/** Resolves and executes the path from the record's current state. */
	void moveToState(ATLActivityRecord r, int finish, boolean excludeLastState) {
		for (int state : getLifecyclePath(r.state, finish, excludeLastState))
			performState(r, state, r.savedState);
	}

	/**
	 * AOSP ActivityThread.callActivityOnStop (ActivityThread.java:6361-6394):
	 * before P onSaveInstanceState runs BEFORE onStop, from P onward AFTER.
	 * ATL supports both, selected by the loaded app's targetSdkVersion (A09).
	 */
	static boolean isPreP(int targetSdkVersion) {
		return targetSdkVersion < 28; /* Build.VERSION_CODES.P */
	}

	/** Posts {@code runnable} on the main looper (single serialized queue). */
	static void postOnMain(Runnable runnable) {
		Looper main = Looper.getMainLooper();
		if (Looper.myLooper() == main)
			new Handler(main).post(runnable);
		else
			runnable.run();
	}
}
