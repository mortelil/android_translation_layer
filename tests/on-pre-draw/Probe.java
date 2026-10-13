// SPDX-License-Identifier: Apache-2.0
package org.atl.onpredraw;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewTreeObserver;

import java.io.File;
import java.io.FileOutputStream;

/**
 * OnPreDraw draw-cycle probe.
 *
 * The window content is one full-size View painted with a distinctive solid
 * colour (opaque magenta, 0xFFFF00FF). A pre-draw listener cancels every frame
 * until a flag flips, so the cancelled frames would have painted that colour but
 * must not be presented as content; once the listener allows the draw, the
 * colour must be present. The screenshot recipe
 * (wait:2;shot:cancelled;wait:5;shot:allowed) therefore distinguishes
 * "callback ran" from "frame was actually drawn".
 *
 * Later stages exercise the ViewTreeObserver lifecycle on AOSP CopyOnWriteArray
 * semantics: a listener that unregisters itself during dispatch, a listener
 * added / a listener removed during dispatch, dropping the last registration and
 * registering again, and a listener still registered when the window is torn
 * down. All stages are recorded to files/results.json and as one stdout line
 * "ONPREDRAW PASS|FAIL ...".
 */
public class Probe extends Activity {
	/** Opaque magenta: distinctive full-window content colour. */
	static final int COLOUR = 0xFFFF00FF;
	/** Keep cancelling until this many ms after start, so the colour is withheld first. */
	static final long ALLOW_AFTER_MS = 3000;
	/** Report (FAIL) even if nothing ran, so a run never ends without results.json. */
	static final long WATCHDOG_MS = 12000;

	View content;
	ViewTreeObserver observer;
	final Handler handler = new Handler();

	boolean allow;
	boolean sawFirstFrame;
	int dispatchCalls, cancelledCalls, allowedCalls;
	int selfCalls, afterCalls, adderCalls, victimCalls, lateCalls, lastCalls;
	boolean stageBStarted, stageCStarted, stageDStarted, reported;
	ViewTreeObserver.OnPreDrawListener victim, adder, last;
	String failure;

	/** Cancels the draw until {@link #allow}, then lets it through. */
	final ViewTreeObserver.OnPreDrawListener main = new ViewTreeObserver.OnPreDrawListener() {
		@Override public boolean onPreDraw() {
			dispatchCalls++;
			if (!sawFirstFrame) {
				sawFirstFrame = true;
				if (content.getWidth() <= 0 || content.getHeight() <= 0)
					fail("pre-draw ran before the view was measured");
			}
			if (!allow) {
				cancelledCalls++;
				return false;
			}
			allowedCalls++;
			if (!stageBStarted) {
				stageBStarted = true;
				handler.postDelayed(stageB, 400);
			}
			return true;
		}
	};

	/** Unregisters itself from inside dispatch; the pass must still reach {@link #after}. */
	final ViewTreeObserver.OnPreDrawListener selfUnreg = new ViewTreeObserver.OnPreDrawListener() {
		@Override public boolean onPreDraw() {
			selfCalls++;
			observer.removeOnPreDrawListener(this);
			return true;
		}
	};

	final ViewTreeObserver.OnPreDrawListener after = new ViewTreeObserver.OnPreDrawListener() {
		@Override public boolean onPreDraw() {
			afterCalls++;
			observer.removeOnPreDrawListener(this);
			return true;
		}
	};

	/** Added during dispatch: must not run in the pass that added it. */
	final ViewTreeObserver.OnPreDrawListener late = new ViewTreeObserver.OnPreDrawListener() {
		@Override public boolean onPreDraw() {
			lateCalls++;
			observer.removeOnPreDrawListener(this);
			return true;
		}
	};

	final Runnable stageB = new Runnable() {
		@Override public void run() {
			// Self-unregistration during dispatch, plus a listener registered after it
			// that must still be reached in the same pass (AOSP snapshot semantics).
			observer.addOnPreDrawListener(selfUnreg);
			observer.addOnPreDrawListener(after);
			handler.postDelayed(stageC, 500);
		}
	};

	final Runnable stageC = new Runnable() {
		@Override public void run() {
			// adder runs first and adds `late` / removes `victim`; `victim` was in the
			// snapshot and must still run in this pass, `late` only in the next one.
			adder = new ViewTreeObserver.OnPreDrawListener() {
				@Override public boolean onPreDraw() {
					adderCalls++;
					if (adderCalls == 1) {
						observer.addOnPreDrawListener(late);
						observer.removeOnPreDrawListener(victim);
						observer.removeOnPreDrawListener(this);
					}
					return true;
				}
			};
			victim = new ViewTreeObserver.OnPreDrawListener() {
				@Override public boolean onPreDraw() {
					victimCalls++;
					observer.removeOnPreDrawListener(this);
					return true;
				}
			};
			observer.addOnPreDrawListener(adder);
			observer.addOnPreDrawListener(victim);
			handler.postDelayed(stageD, 500);
		}
	};

	final Runnable stageD = new Runnable() {
		@Override public void run() {
			// Drop the last registration, then register again: the window-owned
			// observer must be released and re-installed without losing callbacks.
			observer.removeOnPreDrawListener(main);
			last = new ViewTreeObserver.OnPreDrawListener() {
				@Override public boolean onPreDraw() {
					lastCalls++;
					observer.removeOnPreDrawListener(this);
					return true;
				}
			};
			observer.addOnPreDrawListener(last);
			handler.postDelayed(stageE, 600);
		}
	};

	final Runnable stageE = new Runnable() {
		@Override public void run() {
			// Leave one listener registered for the rest of the run: window teardown
			// (the extended recipe's `close:` step, or process exit) must release the
			// window-owned registration without crashing.
			observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
				@Override public boolean onPreDraw() { return true; }
			});
			report();
		}
	};

	void fail(String why) {
		if (failure == null) failure = why;
	}

	void report() {
		if (reported) return;
		reported = true;
		boolean selfOk = selfCalls == 1 && afterCalls == 1;
		boolean churnOk = adderCalls == 1 && victimCalls == 1 && lateCalls == 1;
		boolean lastOk = lastCalls == 1;
		boolean ok = failure == null && cancelledCalls >= 1 && allowedCalls >= 1
				&& selfOk && churnOk && lastOk;
		String status = ok ? "PASS" : "FAIL";
		String detail = "ONPREDRAW " + status
				+ " failure=" + (failure == null ? "none" : failure)
				+ " dispatch=" + dispatchCalls + " cancelled=" + cancelledCalls
				+ " allowed=" + allowedCalls
				+ " self=" + selfCalls + " after=" + afterCalls
				+ " adder=" + adderCalls + " victim=" + victimCalls + " late=" + lateCalls
				+ " last=" + lastCalls;
		System.out.println(detail);
		String json = "{"
				+ "\"status\":\"" + status + "\""
				+ ",\"colour\":\"#FF00FF\""
				+ ",\"detail\":\"" + detail.replace("\"", "'") + "\""
				+ ",\"counts\":{\"dispatch\":" + dispatchCalls
				+ ",\"cancelled\":" + cancelledCalls
				+ ",\"allowed\":" + allowedCalls
				+ ",\"self_unregister\":" + selfCalls
				+ ",\"after_self\":" + afterCalls
				+ ",\"adder\":" + adderCalls
				+ ",\"victim\":" + victimCalls
				+ ",\"late\":" + lateCalls
				+ ",\"last\":" + lastCalls + "}"
				+ ",\"stages\":{"
				+ "\"cancel_before_allow\":" + (cancelledCalls >= 1)
				+ ",\"allowed_draw\":" + (allowedCalls >= 1)
				+ ",\"self_unregistration_in_dispatch\":" + selfOk
				+ ",\"add_remove_in_dispatch\":" + churnOk
				+ ",\"last_listener_transition\":" + lastOk
				+ ",\"listener_kept_for_teardown\":true}"
				+ "}";
		try {
			File dir = getFilesDir();
			dir.mkdirs();
			FileOutputStream out = new FileOutputStream(new File(dir, "results.json"));
			try {
				out.write(json.getBytes("UTF-8"));
			} finally {
				out.close();
			}
		} catch (Exception e) {
			System.out.println("ONPREDRAW FAIL results_write " + e);
		}
	}

	@Override public void onCreate(Bundle state) {
		super.onCreate(state);
		content = new View(this);
		content.setBackgroundColor(COLOUR);
		setContentView(content);
		observer = content.getViewTreeObserver();
		observer.addOnPreDrawListener(main);
		handler.postDelayed(new Runnable() {
			@Override public void run() { allow = true; }
		}, ALLOW_AFTER_MS);
		handler.postDelayed(new Runnable() {
			@Override public void run() { fail("timeout: stages did not complete"); report(); }
		}, WATCHDOG_MS);
	}
}
