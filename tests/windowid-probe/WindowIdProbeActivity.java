// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.windowid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowId;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Exercises the View/Window identity contract (R-4):
 *
 *  - two sibling Views in ONE window return the SAME non-null window token and an
 *    EQUAL WindowId (pre-fix: getWindowToken() returned `new Binder()` per call, so
 *    siblings were never equal);
 *  - an UNATTACHED View returns a null token and a null WindowId (pre-fix: a fresh
 *    non-null Binder);
 *  - Views in TWO different windows (activity vs. dialog) differ;
 *  - moving a View between windows changes its identity accordingly;
 *  - equals/hashCode stay consistent across repeated calls.
 */
public class WindowIdProbeActivity extends Activity {
	private static final String TAG = "[WID] ";
	private static int failures = 0;
	private static int checks = 0;

	private FrameLayout content;
	private View a;
	private View b;
	private Dialog dialog;
	private View c;
	private FrameLayout dialogContent;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		content = new FrameLayout(this);
		a = new View(this);
		b = new View(this);
		content.addView(a, new FrameLayout.LayoutParams(40, 40));
		content.addView(b, new FrameLayout.LayoutParams(40, 40));

		TextView label = new TextView(this);
		label.setText("WINDOWIDPROBE");
		label.setTextSize(28);
		label.setTextColor(Color.BLACK);
		content.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		setContentView(content);

		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				showSecondWindow();
			}
		}, 2000);
	}

	private void showSecondWindow() {
		dialogContent = new FrameLayout(this);
		c = new View(this);
		dialogContent.addView(c, new FrameLayout.LayoutParams(40, 40));
		dialog = new Dialog(this);
		dialog.setContentView(dialogContent);
		dialog.show();

		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				try {
					runChecks();
				} catch (Throwable t) {
					System.out.println(TAG + "PROBE FAILED: " + t);
					t.printStackTrace(System.out);
					failures++;
				}
				try (java.io.FileOutputStream out = new java.io.FileOutputStream(
				        new java.io.File(getFilesDir(), "results.json"))) {
					out.write(("{\"status\":\"" + (failures == 0 ? "PASS" : "FAIL")
					    + "\",\"checks\":" + checks + ",\"failures\":" + failures + "}").getBytes("UTF-8"));
				} catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
				System.out.println(TAG + "RESULT failures=" + failures);
				System.out.println(TAG + "DONE");
				System.out.flush();
				System.exit(failures == 0 ? 0 : 1);
			}
		}, 2000);
	}

	private static void check(String what, boolean ok, String detail) {
		checks++;
		if (!ok)
			failures++;
		System.out.println(TAG + "ASSERT " + what + ": " + detail + " -> " + (ok ? "PASS" : "FAIL"));
	}

	private void runChecks() {
		View decor = getWindow().getDecorView();

		/* --- unattached View: no window -> null token and null WindowId --- */
		View unattached = new View(this);
		IBinder tu = unattached.getWindowToken();
		WindowId iu = unattached.getWindowId();
		check("unattached/token-null", tu == null, "token=" + tu);
		check("unattached/id-null", iu == null, "windowId=" + iu);

		/* --- two sibling Views in the activity's window --- */
		IBinder ta = a.getWindowToken();
		IBinder tb = b.getWindowToken();
		IBinder tdecor = decor.getWindowToken();
		check("sibling/token-non-null", ta != null && tb != null, "a=" + ta + " b=" + tb);
		check("sibling/token-same", ta != null && ta == tb, "a==b: " + (ta == tb));
		check("sibling/token-equals", ta != null && ta.equals(tb), "a.equals(b)=" + (ta != null && ta.equals(tb)));
		check("decor/same-token", tdecor != null && tdecor == ta, "decor=" + tdecor);

		WindowId ia = a.getWindowId();
		WindowId ib = b.getWindowId();
		check("sibling/id-non-null", ia != null && ib != null, "a=" + ia + " b=" + ib);
		check("sibling/id-equal", ia != null && ia.equals(ib), "equal=" + (ia != null && ia.equals(ib)));
		check("sibling/id-hash-equal", ia != null && ia.hashCode() == ib.hashCode(),
			"hash a=" + (ia != null ? ia.hashCode() : "null") + " b=" + (ib != null ? ib.hashCode() : "null"));
		check("sibling/id-same-instance", ia == ib, "same instance=" + (ia == ib));

		check("equality/null", ia != null && !ia.equals(null), "null is not a window");
		check("equality/foreign", ia != null && !ia.equals("window"), "other type is not a window");

		/* --- repeat calls stay stable --- */
		check("stability/same-token", a.getWindowToken() == ta, "token stable=" + (a.getWindowToken() == ta));
		check("stability/same-id", a.getWindowId() == ia, "id stable=" + (a.getWindowId() == ia));

		/* --- a View in a second window (the dialog) --- */
		IBinder tc = c.getWindowToken();
		WindowId ic = c.getWindowId();
		check("dialog/token-non-null", tc != null, "token=" + tc);
		check("windows/token-differ", tc != null && !tc.equals(ta), "dialog=" + tc + " activity=" + ta);
		check("windows/id-non-null", ic != null, "id=" + ic);
		check("windows/id-unequal", ic != null && ia != null && !ia.equals(ic), "activity=" + ia + " dialog=" + ic);

		/* --- moving a View between windows re-derives its identity --- */
		View moved = new View(this);
		check("move/start-unattached", moved.getWindowToken() == null, "token=" + moved.getWindowToken());

		content.addView(moved, new FrameLayout.LayoutParams(40, 40));
		IBinder tm1 = moved.getWindowToken();
		check("move/into-activity-window", tm1 != null && tm1.equals(ta), "token=" + tm1 + " activity=" + ta);

		content.removeView(moved);
		IBinder tm2 = moved.getWindowToken();
		check("move/after-remove-null", tm2 == null, "token after remove=" + tm2);

		dialogContent.addView(moved, new FrameLayout.LayoutParams(40, 40));
		IBinder tm3 = moved.getWindowToken();
		check("move/into-dialog-window", tm3 != null && tm3.equals(tc), "token=" + tm3 + " dialog=" + tc);
		check("move/identity-changed", tm3 != null && !tm3.equals(ta), "new=" + tm3 + " old=" + ta);
		WindowId im3 = moved.getWindowId();
		check("move/id-follows-window", im3 != null && im3.equals(ic), "moved id=" + im3 + " dialog id=" + ic);
	}
}
