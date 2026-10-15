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
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import android.view.Window;
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
 * The C layer (src/api-impl-jni/app/android_app_Activity.c) keeps only GTK
 * window operations and one host-event seam: every GTK/launcher event enters
 * through {@link #hostEvent(int, Object)} with one of the HOST_EVENT_* codes.
 * HOST_EVENT_NEW_INTENT remains declared for code stability but has no native
 * caller: Java callers (ContextImpl/Instrumentation) reach
 * {@link #resumeActivity(Class, Intent)} directly.
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
		/** Posted teardown already scheduled for this record (exactly-once guard). */
		boolean finishPosted;
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
	 * mapping. ON_RESUME executes performResume, which itself ends with
	 * onPostResume (AOSP Activity.performResume, Activity.java:9521).
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

	/* ------------------------------------------------------------------ *
	 * Host-event seam (the ONLY native -> Java lifecycle entry point)
	 * ------------------------------------------------------------------ */

	/**
	 * Native host event dispatch. {@code arg} is the newly constructed
	 * activity for HOST_EVENT_START and ignored (null) for the GTK-driven
	 * events: back button, window close-request and window ready. The codes
	 * are mirrored by HOST_EVENT_* in
	 * src/api-impl-jni/app/android_app_Activity.c and must stay in sync.
	 */
	public static void hostEvent(int code, Object arg) {
		switch (code) {
			case HOST_EVENT_START:
				get().startActivity((Activity) arg);
				break;
			case HOST_EVENT_BACK:
				get().backPressed();
				break;
			case HOST_EVENT_WINDOW_CLOSE_REQUEST:
				get().closeAll();
				break;
			case HOST_EVENT_WINDOW_READY:
				get().windowReady();
				break;
			default:
				throw new IllegalArgumentException("Unknown host event: " + code);
		}
	}

	/* ------------------------------------------------------------------ *
	 * Host operations: the launcher's lifecycle verbs
	 * ------------------------------------------------------------------ */

	/**
	 * Launches {@code activity} as the new top of the stack. Ordering follows
	 * AOSP ActivityThread.handleLaunchActivity + handleStartActivity
	 * (ActivityThread.java:4585-4640): the covered activity is paused first,
	 * the new one is created/restored/post-created/resumed, and only then is
	 * the covered activity stopped (the GTK model keeps the old activity
	 * invisible, so its stop must be deferred, not skipped).
	 */
	public void startActivity(Activity activity) {
		ATLActivityRecord prev = current();
		ATLActivityRecord r = attach(activity, activity.getIntent());

		if (prev != null && prev.state >= ON_RESUME) {
			performState(prev, ON_PAUSE, null);
			clearWindowFocus(prev);
		}

		performState(r, ON_CREATE, null);

		if (activity.isFinishing() || r.finishing) {
			// finish() inside onCreate: AOSP ActivityThread never starts such an
			// activity (also true for the native handleLaunchActivity
			// isFinishing check). Destroy directly — destroy is never a
			// lifecycle-path walk.
			r.finishing = true;
			performState(r, ON_DESTROY, null);
			detach(r);
			if (prev != null && prev.state == ON_PAUSE)
				acquireWindowFocus(prev);
			updateBackSensitivity();
			if (records.isEmpty())
				nativeCloseIfLast(0, nativeWindowOf(activity));
			return;
		}

		nativeDecorAdd(activity);
		performState(r, ON_START, null);
		// AOSP handleStartActivity restores instance state only when saved state
		// exists (pendingActions.shouldRestoreInstanceState).
		if (r.savedState != null)
			activity.performRestoreInstanceState(r.savedState);
		activity.performPostCreate(r.savedState);
		performState(r, ON_RESUME, null);

		r.hasWindowFocus = true;
		nativeDecorShow(activity);
		activity.onWindowFocusChanged(true);

		if (prev != null && prev.state != ON_STOP && prev.state != ON_DESTROY)
			moveToState(prev, ON_STOP, false);

		updateBackSensitivity();
	}

	/**
	 * Called from Activity.finish(). Activity.finish() has already set its own
	 * finishing flag synchronously (that is what makes finish() inside
	 * onCreate work); this only queues the teardown transaction, at most once
	 * per record.
	 */
	public void finish(final Activity activity) {
		final ATLActivityRecord r = recordFor(activity);
		if (r == null || r.finishPosted)
			return;
		r.finishing = true;
		r.finishPosted = true;
		postOnMain(new Runnable() {
			public void run() {
				performFinish(r);
			}
		});
	}

	private void performFinish(ATLActivityRecord r) {
		if (!records.contains(r))
			return;
		boolean wasTop = records.get(0) == r;
		Activity a = r.activity;

		if (r.state == ON_RESUME)
			performState(r, ON_PAUSE, null);

		// Android resumes the returning activity BEFORE the finishing activity
		// is stopped and destroyed (probe reference S2 return shape:
		// B.onPause -> A.onResume -> B.onStop -> B.onDestroy), so the window is
		// handed back here, before the teardown of the finishing record.
		if (wasTop) {
			clearWindowFocus(r);
			ATLActivityRecord next = records.size() > 1 ? records.get(1) : null;
			if (next != null)
				acquireWindowFocus(next);
		}

		if (r.state != ON_STOP && r.state != ON_DESTROY)
			performState(r, ON_STOP, null);
		// No save around this stop: a finishing activity is never saved
		// (AOSP ActivityThread.callActivityOnStop: !r.activity.mFinished).
		performState(r, ON_DESTROY, null);

		long nw = nativeWindowOf(a);
		nativeDecorRemove(a);
		r.hasWindowFocus = false;
		detach(r);
		updateBackSensitivity();
		nativeCloseIfLast(records.size(), nw);
	}

	/**
	 * Called from Activity.recreate(). A relaunch is a single queued
	 * transaction; the record's relaunching flag suppresses re-entry, and the
	 * instance's changingConfigurations flag is set for the duration of the
	 * relaunch destroy (AOSP ActivityThread.java:6862).
	 */
	public void recreate(final Activity activity) {
		final ATLActivityRecord r = recordFor(activity);
		if (r == null || r.relaunching)
			return;
		r.relaunching = true;
		activity.atlSetChangingConfigurations(true);
		postOnMain(new Runnable() {
			public void run() {
				performRecreate(r);
			}
		});
	}

	private void performRecreate(ATLActivityRecord r) {
		if (!records.contains(r))
			return;
		int index = records.indexOf(r);
		boolean wasTop = index == 0;
		Activity old = r.activity;
		int generation = r.generation + 1;
		boolean preP = isPreP(targetSdk());
		Bundle saved = new Bundle();

		// AOSP ActivityThread.handleRelaunchActivityInner (ActivityThread.java:
		// 6933-6988): pause if not paused, save around the stop per targetSdk,
		// then destroy with finishing=false and relaunch with the PRESERVED
		// record intent (r.activity.mIntent -> customIntent).
		if (r.state == ON_RESUME)
			performState(r, ON_PAUSE, null);
		if (preP)
			old.performSaveInstanceState(saved);
		if (r.state != ON_STOP)
			performState(r, ON_STOP, null);
		if (!preP)
			old.performSaveInstanceState(saved);
		performState(r, ON_DESTROY, null);

		long nw = nativeWindowOf(old);
		nativeDecorRemove(old);
		old.atlSetChangingConfigurations(false);

		Activity fresh;
		try {
			fresh = Activity.internalCreateActivity(old.getClass().getName(), nw, r.intent);
		} catch (ReflectiveOperationException e) {
			Slog.w(TAG, "recreate failed for " + old.getClass().getName(), e);
			records.remove(index);
			updateBackSensitivity();
			nativeCloseIfLast(records.size(), nw);
			return;
		}
		// The new instance replaces the old record in place, carrying the next
		// generation and the saved state produced above.
		ATLActivityRecord nr = new ATLActivityRecord(fresh, r.intent);
		nr.generation = generation;
		nr.savedState = saved;
		records.set(index, nr);

		performState(nr, ON_CREATE, saved);
		if (fresh.isFinishing() || nr.finishing) {
			nr.finishing = true;
			performState(nr, ON_DESTROY, null);
			records.remove(index);
			if (wasTop && index < records.size())
				acquireWindowFocus(records.get(index));
			updateBackSensitivity();
			nativeCloseIfLast(records.size(), nw);
			return;
		}

		nativeDecorAdd(fresh);
		performState(nr, ON_START, null);
		fresh.performRestoreInstanceState(saved);
		fresh.performPostCreate(saved);
		performState(nr, ON_RESUME, null);

		if (wasTop) {
			nr.hasWindowFocus = true;
			nativeDecorShow(fresh);
			fresh.onWindowFocusChanged(true);
		}
		updateBackSensitivity();
	}

	/**
	 * Delivers a new intent to an existing instance of {@code clazz}
	 * (FLAG_ACTIVITY_CLEAR_TOP / singleTask reuse), tearing down everything
	 * above it. AOSP preserves the record intent: the new intent is only
	 * delivered through onNewIntent, so this never assigns target.intent and
	 * never calls setIntent (Activity.performNewIntent, Activity.java:
	 * 9355-9361; ActivityThread.java:6939).
	 */
	public boolean resumeActivity(Class<? extends Activity> clazz, Intent intent) {
		int i = -1;
		for (int index = 0; index < records.size(); index++) {
			if (records.get(index).activity.getClass() == clazz) {
				i = index;
				break;
			}
		}
		if (i < 0)
			return false;

		boolean resumeTop = (i == 0 && records.get(0).state == ON_RESUME);

		while (i > 0) {
			ATLActivityRecord top = records.get(0);
			if (top.state == ON_RESUME)
				performState(top, ON_PAUSE, null);
			if (top.state != ON_STOP && top.state != ON_DESTROY)
				performState(top, ON_STOP, null);
			performState(top, ON_DESTROY, null);
			nativeDecorRemove(top.activity);
			top.hasWindowFocus = false;
			detach(top);
			i--;
		}

		ATLActivityRecord target = records.get(0);
		if (resumeTop) {
			// Android never delivers a new intent to a resumed activity: pause,
			// deliver, resume again (no recreate, the window is kept).
			performState(target, ON_PAUSE, null);
			target.activity.performNewIntent(intent);
			if (!target.finishing)
				performState(target, ON_RESUME, null);
			else
				performFinish(target);
		} else {
			target.activity.performNewIntent(intent);
			if (!target.finishing)
				acquireWindowFocus(target);
			else
				performFinish(target);
		}
		updateBackSensitivity();
		return true;
	}

	/**
	 * Tears the whole stack down (GTK window close-request). The GTK window
	 * itself is NOT closed here: app_exit does that after the close-request
	 * handler returns.
	 */
	public void closeAll() {
		ATLActivityRecord top = current();
		if (top != null && top.state == ON_RESUME)
			performState(top, ON_PAUSE, null);
		if (top != null)
			clearWindowFocus(top);

		for (ATLActivityRecord r : new ArrayList<>(records)) {
			if (r.state != ON_STOP && r.state != ON_DESTROY)
				performState(r, ON_STOP, null);
			performState(r, ON_DESTROY, null);
			nativeDecorRemove(r.activity);
			detach(r);
		}
		updateBackSensitivity();
	}

	/**
	 * The window has usable dimensions. Only the current record is focused:
	 * the retired C code focused every backlog entry, which the GTK model
	 * cannot justify (covered activities are invisible).
	 */
	public void windowReady() {
		ATLActivityRecord r = current();
		if (r == null)
			return;
		r.hasWindowFocus = true;
		r.activity.onWindowFocusChanged(true);
	}

	/** Back button: dispatch to the top activity, or disable if nothing can handle it. */
	public void backPressed() {
		ATLActivityRecord r = current();
		if (r == null) {
			nativeSetBackSensitive(false);
			return;
		}
		if (records.size() > 1 || overridesOnBackPressed(r.activity))
			r.activity.onBackPressed();
		else
			nativeSetBackSensitive(false);
	}

	/** True for the bottom-most record (the launcher task root). */
	public boolean isTaskRoot(Activity activity) {
		return records.isEmpty() || records.get(records.size() - 1).activity == activity;
	}

	public boolean hasWindowFocus(Activity activity) {
		ATLActivityRecord r = recordFor(activity);
		return r != null && r.hasWindowFocus;
	}

	/* ------------------------------------------------------------------ *
	 * Helpers
	 * ------------------------------------------------------------------ */

	private ATLActivityRecord current() {
		return records.isEmpty() ? null : records.get(0);
	}

	/** Resumes the record, shows its decor and flips window focus. */
	private void acquireWindowFocus(ATLActivityRecord r) {
		moveToState(r, ON_RESUME, false);
		if (r.finishing)
			return;
		r.hasWindowFocus = true;
		nativeDecorShow(r.activity);
		r.activity.onWindowFocusChanged(true);
	}

	/**
	 * Unconditional focus loss, matching the retired C activity_unfocus(): the
	 * callback is emitted even if the record already believed it was
	 * unfocused.
	 */
	private static void clearWindowFocus(ATLActivityRecord r) {
		r.hasWindowFocus = false;
		r.activity.onWindowFocusChanged(false);
	}

	private void updateBackSensitivity() {
		ATLActivityRecord r = current();
		nativeSetBackSensitive(r != null && (records.size() > 1 || overridesOnBackPressed(r.activity)));
	}

	/**
	 * Whether the activity's class resolves an onBackPressed of its own. The
	 * retired C code compared MethodIDs against Activity.onBackPressed; this is
	 * the same test through reflection.
	 */
	private static boolean overridesOnBackPressed(Activity a) {
		try {
			return a.getClass().getMethod("onBackPressed").getDeclaringClass() != Activity.class;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	private static int targetSdk() {
		return ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.targetSdkVersion;
	}

	private static long nativeWindowOf(Activity a) {
		Window w = a.getWindow();
		return w != null ? w.native_window : 0;
	}

	/* ------------------------------------------------------------------ *
	 * Native GTK window operations (implemented in android_app_Activity.c)
	 * ------------------------------------------------------------------ */

	private static native void nativeDecorAdd(Activity activity);
	private static native void nativeDecorRemove(Activity activity);
	private static native void nativeDecorShow(Activity activity);
	private static native void nativeCloseIfLast(int remaining, long native_window);
	private static native void nativeSetBackSensitive(boolean sensitive);

	/**
	 * AOSP ActivityThread.callActivityOnStop (ActivityThread.java:6361-6394):
	 * before P onSaveInstanceState runs BEFORE onStop, from P onward AFTER.
	 * ATL supports both, selected by the loaded app's targetSdkVersion (A09).
	 */
	public static boolean isPreP(int targetSdkVersion) {
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
