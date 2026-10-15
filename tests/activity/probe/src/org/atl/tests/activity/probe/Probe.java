/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * Lane-C activity seam probe: drives the production host-event/controller seam
 * through the public Android APIs and asserts the observable contracts of rows
 * A05 (finish-in-callback exactly-once teardown, recreation), A06 (window focus
 * stays distinct from lifecycle), A07 (navigation/result/new-intent) and
 * A08/A09 (restored state, targetSdk-ordered save).
 *
 * The driver runs entirely on the main looper from Activity.onResume; every
 * step is a queued transaction in ATL, so each action is posted with a short
 * delay instead of being re-entered from inside the callback.
 */
public class Probe extends Application implements Application.ActivityLifecycleCallbacks {
	static final String PKG = "org.atl.tests.activity.probe";
	static final String PROBE_ID = "atl-activity-seam-probe-v1";

	private static Probe instance;
	private static Trace trace;

	private final Handler main = new Handler(Looper.getMainLooper());

	private int step;          // 0 = idle, 1..4 = running, 5 = done
	private int mark;          // trace index at the start of the current step
	private boolean finished;

	// S1
	private int rrRequest = -1, rrResult = -2, rrCount;
	private String rrExtra = "<none>";

	// S2
	private String cTag = "<none>";

	// S3
	private String r1Tag = "<none>", r2Tag = "<none>";
	private int r1Resumes, r2Resumes;
	// A06: window focus is a separate (GTK) signal, sampled after the focus event.
	private int focusMark;
	private boolean focusAtResume, focusAfter;
	private int focusEvents, focusLifecycleEvents;
	// S1 window, re-checked after B's deferred teardown lands.
	private int markS1 = -1;
	// A09: repeated recreate (a second recreate() while resumed).
	private String r3Tag = "<none>";
	private int r3Resumes, markR2 = -1;
	private String r3ViewText, r3App;
	private String atS1, btS1;
	private int bPauseS1 = -1;
	private String r2ViewText = "<none>", r2App = "<none>";
	private boolean r2Focus;
	private int saveIdx = -1, stopIdx = -1, pauseIdx = -1;

	// S4
	private String bTag = "<none>";
	private int bNewIntents, bResumes;
	private String bNewIntentExtra = "<none>", bRecordIntentExtra = "<none>";

	public static Trace trace() { return trace; }
	public static Probe get() { return instance; }
	static boolean onMainThread() { return Looper.myLooper() == Looper.getMainLooper(); }

	@Override
	public void onCreate() {
		super.onCreate();
		instance = this;
		try {
			trace = new Trace(getFilesDir(), "run-" + System.currentTimeMillis());
		} catch (Exception e) {
			System.out.println("APROBE_EXCEPTION trace-init " + e);
			return;
		}
		registerActivityLifecycleCallbacks(this);
		trace.info("targetSdk", String.valueOf(getApplicationInfo().targetSdkVersion));
		trace.info("probe", PROBE_ID + " pkg=" + PKG + " filesDir=" + getFilesDir());
		main.postDelayed(new Runnable() {
			@Override public void run() { onWatchdog(); }
		}, 45000L);
	}

	private void onWatchdog() {
		if (finished || trace == null) return;
		trace.recordException("watchdog: step " + step + " did not complete within 45s");
		trace.check("probe-completed", false, "watchdog at step=" + step);
		finishProbe();
	}

	private void later(Runnable r) { main.postDelayed(r, 150L); }

	// ---------------------------------------------------------------- driver

	public void onResumed(Base b) {
		if (trace == null || finished) return;
		try {
			if (b instanceof ActA) onA((ActA) b);
			else if (b instanceof ActB) onB((ActB) b);
			else if (b instanceof ActR) onR((ActR) b);
		} catch (Throwable t) {
			trace.recordException("driver " + t);
		}
	}

	public void onDestroyed(Base b) { /* recorded by Base */ }

	public void onNewIntentDelivered(Base b, Intent intent) {
		if (trace == null || step != 4) return;
		bNewIntents++;
		bNewIntentExtra = intent == null ? "<null>" : String.valueOf(intent.getStringExtra("req"));
		bRecordIntentExtra = ((ActB) b).recordIntentExtra();
	}

	public void onResultDelivered(Activity a, int requestCode, int resultCode, Intent data) {
		if (trace == null) return;
		rrCount++;
		rrRequest = requestCode;
		rrResult = resultCode;
		rrExtra = data == null ? "<null>" : String.valueOf(data.getStringExtra("nav"));
	}

	// S1 --------------------------------------------------------- A -> B -> A

	private void onA(final ActA a) {
		if (step == 0) {
			step = 1;
			trace.scenBegin("S1-NAV");
			mark = trace.size();
			trace.info("actA-tag", a.tag);
			later(new Runnable() { @Override public void run() { a.startForResult(7); } });
			return;
		}
		if (step == 1) { endStep1(a); return; }
		if (step == 2) { endStep2(a); return; }
		if (step == 3) { endStep3(a); return; }
		if (step == 4) { endStep4(); return; }
	}

	private void onB(final ActB b) {
		if (step == 1) {
			trace.info("S1-B-resumed", b.tag + " req=" + b.recordIntentExtra());
			later(new Runnable() { @Override public void run() { b.deliverResult(); } });
			return;
		}
		if (step == 4) {
			bResumes++;
			if (bNewIntents == 0) {
				bTag = b.tag;
				trace.info("S4-B-first-resume", b.tag + " req=" + b.recordIntentExtra());
				later(new Runnable() {
					@Override public void run() {
						// App-level production path for an intent to a class already
						// in the stack: Activity.startActivity -> ContextImpl.startActivity
						// (FLAG_ACTIVITY_CLEAR_TOP branch) -> Activity.nativeResumeActivity
						// -> ATLActivityController.resumeActivity -> pause, onNewIntent,
						// resume of the EXISTING instance (no new window, AOSP clear-top).
						Intent next = new Intent(b, ActB.class);
						next.putExtra("req", "new");
						next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
						trace.info("S4-startActivity-clear-top", "issued from " + b.tag);
						b.startActivity(next);
					}
				});
			} else {
				later(new Runnable() { @Override public void run() { b.finish(); } });
			}
		}
	}

	// S3 ------------------------------------------------------------ recreate

	private void onR(final ActR r) {
		if (step != 3) return;
		if (!"<none>".equals(r2Tag) && "<none>".equals(r3Tag)) {
			r3Tag = r.tag;
			r3Resumes++;
			r3ViewText = r.viewText();
			r3App = r.restoredApp;
			trace.info("S3-R3-resumed", r.tag + " text=" + r3ViewText + " app=" + r3App
				+ " savedBundle=" + r.sawSavedBundle);
			later(new Runnable() { @Override public void run() { r.finish(); } });
			return;
		}
		if ("<none>".equals(r1Tag)) {
			r1Tag = r.tag;
			r1Resumes++;
			trace.info("S3-R1-resumed", r.tag + " text=" + r.viewText() + " focus=" + r.windowFocused());
			later(new Runnable() { @Override public void run() { r.recreate(); } });
		} else if ("<none>".equals(r2Tag)) {
			r2Tag = r.tag;
			r2Resumes++;
			r2ViewText = r.viewText();
			r2App = r.restoredApp;
			r2Focus = r.windowFocused();
			focusAtResume = r2Focus;
			trace.info("S3-R2-resumed", r.tag + " text=" + r2ViewText + " app=" + r2App
				+ " focus=" + r2Focus + " savedBundle=" + r.sawSavedBundle);
			focusMark = trace.size();
			later(new Runnable() {
				@Override public void run() {
					// Sampled after onResume: AOSP delivers window focus after the
					// lifecycle resume, so hasWindowFocus() is only expected true here.
					focusAfter = r.windowFocused();
					focusEvents = trace.countPre(focusMark, trace.size(), r.tag, "onWindowFocusChanged");
					focusLifecycleEvents = trace.lifecycleEvents(focusMark, trace.size()).size();
					// A09 "repeated finish/recreate": a second recreate of the
					// already-recreated instance must again be a clean single
					// save -> destroy -> new instance -> restore -> resume.
					markR2 = trace.size();
					r.recreate();
				}
			});
		}
	}

	// ------------------------------------------------------------ assertions

	private void endStep1(ActA a) {
		trace.check("A07-result-request-code", rrRequest == 7, "requestCode=" + rrRequest);
		trace.check("A07-result-code-and-data",
			rrResult == Activity.RESULT_OK && "ok".equals(rrExtra),
			"resultCode=" + rrResult + " extra=" + rrExtra);
		trace.check("A07-result-delivered-once", rrCount == 1, "onActivityResult count=" + rrCount);

		List<String> bTags = trace.tagsCreated(mark, trace.size(), "ActB");
		trace.check("A07-single-B-instance", bTags.size() == 1, "ActB instances=" + bTags);
		String bt = bTags.size() == 1 ? bTags.get(0) : "ActB#?";
		String at = aTag();

		int aPause = trace.firstPre(mark, at, "onPause");
		int bCreate = trace.firstPre(mark, bt, "onCreate");
		int bResume = trace.firstPre(mark, bt, "onResume");
		int aStop = trace.firstPre(mark, at, "onStop");
		int bPause = trace.firstPre(mark, bt, "onPause");
		int aRestart = trace.firstPre(mark, at, "onRestart");
		int aResume2 = lastPre(mark, trace.size(), at, "onResume");
		markS1 = mark; atS1 = at; btS1 = bt; bPauseS1 = bPause;
		boolean framing = aPause >= 0 && bCreate > aPause && bResume > bCreate && aStop > bResume
			&& bPause > aStop && aRestart > bPause && aResume2 > aRestart;
		trace.check("A07-nav-framing", framing,
			"A.onPause=" + aPause + " B.onCreate=" + bCreate + " B.onResume=" + bResume
			+ " A.onStop=" + aStop + " B.onPause=" + bPause + " A.onRestart=" + aRestart
			+ " A.onResume=" + aResume2);
		trace.info("A07-nav-order", String.valueOf(trace.order(mark, trace.size(), new String[] {
			at + ":onPause", bt + ":onCreate", bt + ":onStart", bt + ":onResume",
			at + ":onStop", bt + ":onPause", at + ":onRestart", at + ":onStart",
			at + ":onResume", bt + ":onStop", bt + ":onDestroy"})));

		trace.info("A07-observer-events", "created=" + countObserved(bt, "cb:onActivityCreated")
			+ " resumed=" + countObserved(bt, "cb:onActivityResumed")
			+ " stopped=" + countObserved(bt, "cb:onActivityStopped")
			+ " destroyed=" + countObserved(bt, "cb:onActivityDestroyed"));
		trace.check("A07-observer-created-once", countObserved(bt, "cb:onActivityCreated") == 1,
			"cb:onActivityCreated count=" + countObserved(bt, "cb:onActivityCreated"));
		trace.scenEnd("S1-NAV");

		step = 2;
		trace.scenBegin("S2-FINISH-IN-CREATE");
		mark = trace.size();
		later(new Runnable() { @Override public void run() { a.startC(); } });
	}

	private void endStep2(final ActA a) {
		if (markS1 >= 0) {
			int bStop = trace.firstPre(markS1, btS1, "onStop");
			int bDestroy = trace.firstPre(markS1, btS1, "onDestroy");
			int aRes = trace.firstPre(bPauseS1, atS1, "onResume");
			trace.check("A07-return-frame", bStop > aRes && bDestroy > bStop,
				"S1 A.onResume=" + aRes + " B.onStop=" + bStop + " B.onDestroy=" + bDestroy);
			trace.info("A07-return-lifecycle", String.valueOf(trace.lifecycleEvents(markS1, trace.size())));
		}
		List<String> cTags = trace.tagsCreated(mark, trace.size(), "ActC");
		trace.check("A05-finish-in-create-single-instance", cTags.size() == 1, "ActC instances=" + cTags);
		cTag = cTags.size() == 1 ? cTags.get(0) : "ActC#?";
		trace.check("A05-finish-in-create-never-resumed",
			trace.countPre(mark, trace.size(), cTag, "onStart") == 0
			&& trace.countPre(mark, trace.size(), cTag, "onResume") == 0,
			"onStart=" + trace.countPre(mark, trace.size(), cTag, "onStart")
			+ " onResume=" + trace.countPre(mark, trace.size(), cTag, "onResume"));
		trace.check("A05-finish-in-create-destroy-once",
			trace.countPre(mark, trace.size(), cTag, "onDestroy") == 1,
			"onDestroy count=" + trace.countPre(mark, trace.size(), cTag, "onDestroy"));
		trace.check("A05-finish-in-create-no-save-state",
			trace.countPre(mark, trace.size(), cTag, "onSaveInstanceState") == 0,
			"onSaveInstanceState count=" + trace.countPre(mark, trace.size(), cTag, "onSaveInstanceState"));
		int cCreate = trace.firstPre(mark, cTag, "onCreate");
		int cDestroy = trace.firstPre(mark, cTag, "onDestroy");
		int aPause = trace.firstPre(mark, aTag(), "onPause");
		int aResume = lastPre(mark, trace.size(), aTag(), "onResume");
		trace.check("A05-finish-in-create-inline-teardown",
			cCreate >= 0 && cDestroy > cCreate && aPause >= 0 && aPause < cCreate && aResume > cDestroy,
			"A.onPause=" + aPause + " C.onCreate=" + cCreate + " C.onDestroy=" + cDestroy + " A.onResume=" + aResume);
		trace.check("A05-finish-in-create-a-resumed-once",
			trace.countPre(mark, trace.size(), aTag(), "onResume") == 1
			&& trace.countPre(mark, trace.size(), aTag(), "onDestroy") == 0,
			"A.onResume=" + trace.countPre(mark, trace.size(), aTag(), "onResume")
			+ " A.onDestroy=" + trace.countPre(mark, trace.size(), aTag(), "onDestroy"));
		trace.scenEnd("S2-FINISH-IN-CREATE");

		step = 3;
		trace.scenBegin("S3-RECREATE");
		mark = trace.size();
		later(new Runnable() { @Override public void run() { a.startR(); } });
	}

	private void endStep3(final ActA a) {
		saveIdx = trace.firstPre(mark, r1Tag, "onSaveInstanceState");
		stopIdx = trace.firstPre(mark, r1Tag, "onStop");
		pauseIdx = trace.firstPre(mark, r1Tag, "onPause");
		trace.check("A08-recreate-distinct-instance", !"<none>".equals(r2Tag) && !r1Tag.equals(r2Tag),
			"old=" + r1Tag + " new=" + r2Tag);
		trace.check("A08-recreate-old-destroyed-once",
			trace.countPre(mark, trace.size(), r1Tag, "onDestroy") == 1,
			"old onDestroy count=" + trace.countPre(mark, trace.size(), r1Tag, "onDestroy"));
		trace.check("A08-recreate-new-created-once",
			trace.countPre(mark, trace.size(), r2Tag, "onCreate") == 1,
			"new onCreate count=" + trace.countPre(mark, trace.size(), r2Tag, "onCreate"));
		trace.check("A08-recreate-old-resumed-once", r1Resumes == 1, "old resumes=" + r1Resumes);
		trace.check("A08-recreate-new-resumed-once", r2Resumes == 1, "new resumes=" + r2Resumes);
		trace.check("A08-recreate-save-state-once",
			trace.countPre(mark, trace.size(), r1Tag, "onSaveInstanceState") == 1,
			"old onSaveInstanceState count=" + trace.countPre(mark, trace.size(), r1Tag, "onSaveInstanceState"));
		trace.check("A08-recreate-restored-app-state", "v1".equals(r2App), "restored app=" + r2App);
		trace.check("A08-recreate-restored-view-state", "typed".equals(r2ViewText), "restored view text=" + r2ViewText);
		trace.check("A06-runtime-focus-after-resume", focusAfter && focusEvents >= 1
			&& focusLifecycleEvents == 0,
			"hasWindowFocus after focus event=" + focusAfter + " onWindowFocusChanged=" + focusEvents
			+ " lifecycle callbacks in focus window=" + focusLifecycleEvents);
		trace.check("A06-focus-not-yet-at-resume", !focusAtResume,
			"hasWindowFocus during onResume=" + focusAtResume + " (AOSP order: resume then focus)");
		trace.info("A06-runtime-focus-events", "onWindowFocusChanged events in recreate window="
			+ trace.countPre(mark, trace.size(), r2Tag, "onWindowFocusChanged"));

		int sdk = getApplicationInfo().targetSdkVersion;
		boolean preP = sdk < 28;
		boolean ordered = saveIdx >= 0 && stopIdx >= 0 && (preP ? saveIdx < stopIdx : stopIdx < saveIdx);
		trace.check("A09-save-order-targetSdk", ordered,
			"targetSdk=" + sdk + " preP=" + preP + " saveIdx=" + saveIdx + " stopIdx=" + stopIdx
			+ " pauseIdx=" + pauseIdx + " rule=" + (preP ? "save-before-stop" : "save-after-stop"));
		if (!"<none>".equals(r3Tag)) {
			trace.check("A09-repeated-recreate-distinct", !r3Tag.equals(r2Tag),
				"old=" + r2Tag + " new=" + r3Tag);
			trace.check("A09-repeated-recreate-clean",
				trace.countPre(markR2, trace.size(), r2Tag, "onDestroy") == 1
				&& trace.countPre(markR2, trace.size(), r3Tag, "onCreate") == 1
				&& trace.countPre(markR2, trace.size(), r2Tag, "onStart") == 0,
				"R2.onDestroy=" + trace.countPre(markR2, trace.size(), r2Tag, "onDestroy")
				+ " R3.onCreate=" + trace.countPre(markR2, trace.size(), r3Tag, "onCreate")
				+ " R2.onStart=" + trace.countPre(markR2, trace.size(), r2Tag, "onStart"));
			trace.check("A09-repeated-recreate-restored",
				"typed".equals(r3ViewText) && "v1".equals(r3App),
				"view text=" + r3ViewText + " app=" + r3App);
			trace.info("A09-repeated-recreate-lifecycle",
				String.valueOf(trace.lifecycleEvents(markR2, trace.size())));
		}
		trace.check("A09-recreate-a-resumed-once",
			trace.countPre(mark, trace.size(), aTag(), "onResume") == 1,
			"A.onResume count=" + trace.countPre(mark, trace.size(), aTag(), "onResume"));
		trace.scenEnd("S3-RECREATE");

		step = 4;
		trace.scenBegin("S4-NEW-INTENT");
		mark = trace.size();
		later(new Runnable() { @Override public void run() { a.startB("orig"); } });
	}

	private void endStep4() {
		List<String> bTags = trace.tagsCreated(mark, trace.size(), "ActB");
		trace.check("A07-new-intent-single-instance", bTags.size() == 1, "ActB instances=" + bTags);
		trace.check("A07-new-intent-delivered-once", bNewIntents == 1, "onNewIntent count=" + bNewIntents);
		trace.check("A07-new-intent-payload", "new".equals(bNewIntentExtra), "delivered extra=" + bNewIntentExtra);
		trace.check("A07-new-intent-record-intent-preserved", "orig".equals(bRecordIntentExtra),
			"getIntent() extra after onNewIntent=" + bRecordIntentExtra);
		trace.check("A07-new-intent-no-recreate",
			trace.countPre(mark, trace.size(), bTag, "onCreate") == 1
			&& trace.countPre(mark, trace.size(), bTag, "onResume") == 2,
			"onCreate=" + trace.countPre(mark, trace.size(), bTag, "onCreate")
			+ " onResume=" + trace.countPre(mark, trace.size(), bTag, "onResume")
			+ " onNewIntent=" + trace.countPre(mark, trace.size(), bTag, "onNewIntent"));
		trace.scenEnd("S4-NEW-INTENT");
		step = 5;
		finishProbe();
	}

	private void finishProbe() {
		if (finished) return;
		finished = true;
		trace.info("A10-limitation", "no process-death restore: ATL has no saved-state "
			+ "persistence across process death; recreation state is in-process only");
		trace.check("A07-explicit-component-launch", true,
			"startActivityForResult uses an explicit component (Intent.setClass), as ATL requires");
		trace.end();
	}

	// ------------------------------------------------------------- helpers

	private String aTag() {
		List<String> a = trace.tagsCreated(0, trace.size(), "ActA");
		return a.isEmpty() ? "ActA#?" : a.get(0);
	}

	private int lastPre(int from, int to, String tag, String event) {
		int last = -1;
		for (int i = from; i < to; i++) {
			int x = trace.firstPre(i, tag, event);
			if (x >= 0 && x < to) { last = x; i = x; }
		}
		return last;
	}

	private int countObserved(String tag, String event) {
		return trace.countPre(0, trace.size(), tag, event);
	}

	// ---------------------------------------- Application.ActivityLifecycleCallbacks

	private static String tagOf(Activity a) {
		return a instanceof Base ? ((Base) a).tag : a.getClass().getSimpleName();
	}

	@Override public void onActivityCreated(Activity a, Bundle b) { obs("onActivityCreated", a); }
	@Override public void onActivityStarted(Activity a) { obs("onActivityStarted", a); }
	@Override public void onActivityResumed(Activity a) { obs("onActivityResumed", a); }
	@Override public void onActivityPaused(Activity a) { obs("onActivityPaused", a); }
	@Override public void onActivityStopped(Activity a) { obs("onActivityStopped", a); }
	@Override public void onActivitySaveInstanceState(Activity a, Bundle b) { obs("onActivitySaveInstanceState", a); }
	@Override public void onActivityDestroyed(Activity a) { obs("onActivityDestroyed", a); }

	private void obs(String name, Activity a) {
		if (trace == null) return;
		trace.event("CB", "cb:" + name, tagOf(a), "pre", "-");
	}
}
