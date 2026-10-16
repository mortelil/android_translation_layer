// SPDX-License-Identifier: Apache-2.0
// Deterministic probe for the bounded android.view surface fixes:
//   * Window.setColorMode/getColorMode (COLOR_MODE_DEFAULT/WIDE_COLOR_GAMUT/HDR,
//     invalid values rejected with IllegalArgumentException)
//   * TextureView real SurfaceTexture lifecycle: available / size-changed /
//     destroyed callbacks fire in order; getSurfaceTexture() hands out the real
//     texture while the view is available and the documented (null) value after
//     destruction -- never a dummy SurfaceTexture
//   * a view whose onDetachedFromWindow() throws must not abort the VM when it is
//     removed: the original Java exception must reach the removeView() caller
//
// Timing: ATL attaches the window's content view after Activity.onCreate()
// returns (the GTK widget hosting the window is realized later), so every
// attach-dependent check is deferred (Handler.postDelayed, also triggered early
// from onWindowFocusChanged) and every check prints one deterministic
// "SURFACE_LIFECYCLE ok|FAIL <name>" line. The run ends with a single
// "SURFACE_LIFECYCLE RESULT <PASS|FAIL> ..." line plus
// getFilesDir()/results.json, which is the runner's result artifact.
package org.atl.surfacelifecycle;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class Probe extends Activity {
	/** Let ATL realize the window and attach the content view before checking. */
	static final int DEFER_MS = 1500;
	/** Between deferred stages. */
	static final int STEP_MS = 700;

	private final Handler handler = new Handler();
	private final List<String> failures = new ArrayList<String>();
	private final List<String> events = new ArrayList<String>();

	private TextureView tv;
	private FrameLayout root;
	private boolean availableCb, sizeCb, destroyedCb;
	private SurfaceTexture availableTexture;
	private String sizeChangePath = "none";
	/** Observed getSurfaceTexture() value after the listener released it. */
	private String postDestroyGetter = "unset";
	/** How many times the injected throwing onDetachedFromWindow() actually ran. */
	private int detachThrows;
	private boolean stage2Started, stage2bStarted, reported;

	private void check(String what, boolean cond) {
		if (!cond)
			failures.add(what);
		System.out.println("SURFACE_LIFECYCLE " + (cond ? "ok   " : "FAIL ") + what);
	}

	private void ev(String name) {
		events.add(name);
	}

	private int indexOfEvent(String prefix) {
		for (int i = 0; i < events.size(); i++)
			if (events.get(i).startsWith(prefix))
				return i;
		return -1;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);

		// ---- Window.setColorMode / getColorMode: synchronous contract ----
		Window w = getWindow();
		check("color-mode-default", w.getColorMode() == Window.COLOR_MODE_DEFAULT);
		w.setColorMode(1); // COLOR_MODE_WIDE_COLOR_GAMUT
		check("set-color-mode-1-then-get",
				w.getColorMode() == 1 && w.getColorMode() == Window.COLOR_MODE_WIDE_COLOR_GAMUT);
		w.setColorMode(Window.COLOR_MODE_HDR);
		check("set-color-mode-hdr-then-get", w.getColorMode() == Window.COLOR_MODE_HDR);
		boolean rejected = false;
		try {
			w.setColorMode(-1);
		} catch (IllegalArgumentException e) {
			rejected = true;
		}
		check("set-color-mode-neg1-throws-IAE", rejected);
		check("color-mode-unchanged-after-reject", w.getColorMode() == Window.COLOR_MODE_HDR);
		w.setColorMode(Window.COLOR_MODE_DEFAULT);

		// ---- TextureView: pre-attach state ----
		tv = new TextureView(this);
		check("texture-null-before-attach", tv.getSurfaceTexture() == null);
		check("not-available-before-attach", !tv.isAvailable());
		tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
			@Override public void onSurfaceTextureAvailable(SurfaceTexture s, int width, int height) {
				availableCb = true;
				availableTexture = s;
				ev("available:" + width + "x" + height);
			}
			@Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int width, int height) {
				sizeCb = true;
				ev("size-changed:" + width + "x" + height);
			}
			@Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
				destroyedCb = true;
				ev("destroyed");
				return true; // the framework may release it
			}
			@Override public void onSurfaceTextureUpdated(SurfaceTexture s) {
				ev("updated");
			}
		});
		tv.setOpaque(false);
		check("opaque-roundtrip", !tv.isOpaque());
		Matrix m = new Matrix();
		m.setScale(2f, 3f);
		tv.setTransform(m);
		Matrix got = tv.getTransform(null);
		float[] v = new float[9];
		got.getValues(v);
		check("transform-roundtrip", v[Matrix.MSCALE_X] == 2f && v[Matrix.MSCALE_Y] == 3f);

		root = new FrameLayout(this);
		root.addView(tv, new ViewGroup.LayoutParams(64, 48));
		setContentView(root);

		handler.postDelayed(stage2, DEFER_MS);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		// Focus arrives once the window is really mapped: an equivalent, often
		// earlier trigger for the deferred stage.
		if (hasFocus && !stage2Started)
			handler.postDelayed(stage2, 100);
	}

	/** Post-attach: available callbacks, real texture, then force a resize. */
	private final Runnable stage2 = new Runnable() {
		@Override public void run() {
			if (stage2Started)
				return;
			stage2Started = true;

			check("texture-available-after-attach", tv.isAvailable());
			check("available-callback-fired", availableCb);
			check("getter-returns-real-texture",
					tv.getSurfaceTexture() != null && tv.getSurfaceTexture() == availableTexture);
			check("getter-not-released",
					tv.getSurfaceTexture() != null && !tv.getSurfaceTexture().isReleased());
			check("available-fired-before-size-changed",
					indexOfEvent("available") >= 0
							&& (indexOfEvent("size-changed") < 0
									|| indexOfEvent("available") < indexOfEvent("size-changed")));

			// A real size change must reach the listener.
			tv.setLayoutParams(new FrameLayout.LayoutParams(96, 72));
			tv.requestLayout();
			handler.postDelayed(stage2b, STEP_MS);
		}
	};

	/** A 0x0 view has no geometry, so drive View.layout() directly if the ATL
	 *  layout pass did not deliver the resize on its own. Records which path
	 *  produced the size-changed callback (never silently claims the other). */
	private final Runnable stage2b = new Runnable() {
		@Override public void run() {
			if (stage2bStarted)
				return;
			stage2bStarted = true;

			if (sizeCb) {
				sizeChangePath = "layout-pass";
			} else {
				tv.layout(0, 0, 96, 72);
				sizeChangePath = sizeCb ? "direct-layout" : "none";
			}
			check("size-changed-callback-fired", sizeCb);
			check("available-index-before-size-changed",
					indexOfEvent("available") >= 0
							&& indexOfEvent("size-changed") > indexOfEvent("available"));

			// Detach: the listener must be told the texture is going away.
			SurfaceTexture beforeDestroy = tv.getSurfaceTexture();
			root.removeView(tv);
			check("destroyed-callback-fired", destroyedCb);
			check("not-available-after-destroy", !tv.isAvailable());
			check("getter-after-destroy-is-null-or-released",
					tv.getSurfaceTexture() == null || tv.getSurfaceTexture().isReleased());
			postDestroyGetter = tv.getSurfaceTexture() == null ? "null"
					: (tv.getSurfaceTexture().isReleased() ? "released" : "live-dummy");
			check("post-destroy-getter-not-live", !"live-dummy".equals(postDestroyGetter));
			check("destroyed-texture-was-released",
					beforeDestroy != null && beforeDestroy.isReleased());
			check("available-before-destroyed",
					indexOfEvent("destroyed") > indexOfEvent("available"));

			// Re-attach: a released texture must not be handed out again.
			root.addView(tv, new ViewGroup.LayoutParams(96, 72));
			SurfaceTexture fresh = tv.getSurfaceTexture();
			check("re-attach-creates-fresh-texture", fresh != null && !fresh.isReleased());
			check("fresh-texture-differs-from-released-one",
					fresh != null && fresh != beforeDestroy);

			handler.postDelayed(stage3, STEP_MS);
		}
	};

	/** Cleanup path: an injected throwing onDetachedFromWindow. */
	private final Runnable stage3 = new Runnable() {
		@Override public void run() {
			final RuntimeException boom = new RuntimeException("probe-detach-throw");
			View thrower = new View(Probe.this) {
				@Override protected void onDetachedFromWindow() {
					detachThrows++;
					throw boom;
				}
			};
			FrameLayout holder = new FrameLayout(Probe.this);
			holder.addView(thrower, new ViewGroup.LayoutParams(16, 16));
			root.addView(holder, new ViewGroup.LayoutParams(32, 32));

			boolean propagated = false;
			boolean aborted = false;
			try {
				holder.removeView(thrower);
			} catch (RuntimeException e) {
				propagated = (e == boom);
			} catch (Throwable t) {
				aborted = true;
				failures.add("detach-threw-" + t.getClass().getName());
				System.out.println("SURFACE_LIFECYCLE FAIL detach-threw-" + t.getClass().getName());
			}
			check("detach-exception-propagates-not-aborts", propagated && !aborted);
			check("detach-exception-thrown-once", detachThrows == 1);

			// The VM must still be alive and able to run framework code.
			check("process-alive-after-detach-throw",
					root.getChildCount() >= 1 && tv.isAvailable());
			report();
		}
	};

	private void report() {
		if (reported)
			return;
		reported = true;
		boolean ok = failures.isEmpty();
		StringBuilder sb = new StringBuilder();
		for (String e : events)
			sb.append(sb.length() == 0 ? "" : ",").append(e);
		String status = ok ? "PASS" : "FAIL";
		String detail = "SURFACE_LIFECYCLE RESULT " + status
				+ " checks_failed=" + failures.size()
				+ " size_change_path=" + sizeChangePath
				+ " post_destroy_getter=" + postDestroyGetter
				+ " detach_throws=" + detachThrows
				+ " events=[" + sb + "]"
				+ " failures=[" + join(failures) + "]";
		System.out.println(detail);
		writeResults(ok, sb.toString(), detail);
		finish();
	}

	private static String join(List<String> list) {
		StringBuilder sb = new StringBuilder();
		for (String s : list)
			sb.append(sb.length() == 0 ? "" : ";").append(s);
		return sb.toString();
	}

	private void writeResults(boolean ok, String eventsCsv, String detail) {
		try {
			File dir = getFilesDir();
			if (dir != null && !dir.exists())
				dir.mkdirs();
			File out = new File(dir, "results.json");
			FileOutputStream fos = new FileOutputStream(out);
			String json = "{\"status\":\"" + (ok ? "PASS" : "FAIL") + "\""
					+ ",\"package\":\"org.atl.surfacelifecycle\""
					+ ",\"size_change_path\":\"" + sizeChangePath + "\""
					+ ",\"post_destroy_getter\":\"" + postDestroyGetter + "\""
					+ ",\"detach_throws\":" + detachThrows
					+ ",\"events\":\"" + eventsCsv.replace("\"", "'") + "\""
					+ ",\"failed_checks\":\"" + join(failures).replace("\"", "'") + "\""
					+ ",\"detail\":\"" + detail.replace("\"", "'") + "\"}\n";
			fos.write(json.getBytes("UTF-8"));
			fos.close();
			System.out.println("SURFACE_LIFECYCLE results written to " + out.getAbsolutePath());
		} catch (Exception e) {
			System.out.println("SURFACE_LIFECYCLE FAIL results-write: " + e);
		}
	}
}
