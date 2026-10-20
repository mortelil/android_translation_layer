// SPDX-License-Identifier: Apache-2.0
package org.atl.surfaceviewrender;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;

/** Checks that a SurfaceView draws below a visible, clickable Android control. */
public final class Probe extends Activity implements SurfaceHolder.Callback {
	private SurfaceView surface;

	@Override public void onCreate(Bundle state) {
		super.onCreate(state);
		android.graphics.drawable.ColorDrawable fade = new android.graphics.drawable.ColorDrawable(0x80abcdef);
		fade.setAlpha(128);
		check(fade.getColor() == 0x40abcdef, "base alpha modulation");
		fade.setAlpha(128);
		check(fade.getColor() == 0x40abcdef, "alpha is not cumulative");
		fade.setAlpha(255);
		check(fade.getColor() == 0x80abcdef, "restore base alpha");
		fade.setColor(Color.BLACK);
		check(fade.getColor() == Color.BLACK, "setColor resets alpha");
		fade.setAlpha(0);
		check(fade.getOpacity() == android.graphics.PixelFormat.TRANSPARENT, "transparent opacity");
		System.out.println("COLOR_ALPHA contracts PASS");
		surface = new SurfaceView(this);
		surface.getHolder().addCallback(this);
		FrameLayout root = new FrameLayout(this);
		root.addView(surface, new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		android.view.View scrim = new android.view.View(this);
		scrim.setBackground(fade);
		root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
		int layout = getResources().getIdentifier("transparent_cover", "layout", getPackageName());
		android.view.View xmlCover = getLayoutInflater().inflate(layout, root, false);
		check(xmlCover.getAlpha() == 0.0f, "XML alpha");
		root.addView(xmlCover);
		System.out.println("XML_ALPHA PASS");
		Button controls = new Button(this);
		controls.setText("SurfaceView controls: tap");
		xmlCover.setOnClickListener(new android.view.View.OnClickListener() {
			@Override public void onClick(android.view.View view) {
				controls.setText("Transparent input responds");
				System.out.println("XML_ALPHA transparent click delivered");
			}
		});
		controls.setTextColor(Color.BLACK);
		FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		root.addView(controls, controlsParams);
		controls.setOnClickListener(new android.view.View.OnClickListener() {
			@Override public void onClick(android.view.View view) {
				controls.setText("Controls respond");
				System.out.println("SURFACEVIEW_RENDER overlay click delivered");
			}
		});
		setContentView(root);
	}

	private static void check(boolean condition, String name) {
		if (!condition) throw new AssertionError(name);
	}

	@Override public void surfaceCreated(SurfaceHolder holder) {
		System.out.println("SURFACEVIEW_RENDER created");
	}

	@Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Canvas canvas = holder.lockCanvas();
		if (canvas == null) {
			System.out.println("SURFACEVIEW_RENDER FAIL lockCanvas=null");
			return;
		}
		try {
			canvas.drawColor(Color.MAGENTA);
			Paint paint = new Paint();
			paint.setColor(Color.GREEN);
			canvas.drawRect(100, 100, width - 100, height - 100, paint);
			System.out.println("SURFACEVIEW_RENDER drew " + width + "x" + height);
		} finally {
			holder.unlockCanvasAndPost(canvas);
		}
	}

	@Override public void surfaceDestroyed(SurfaceHolder holder) {
		System.out.println("SURFACEVIEW_RENDER destroyed");
	}
}
