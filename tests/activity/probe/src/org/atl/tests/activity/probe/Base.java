/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared framing for the probe activities: every lifecycle override logs a
 * pre-super line before {@code super.<method>()} and a post-super line after,
 * so both the order and the position of the framework's own work are visible.
 * The per-instance tag (ActR#2) proves Activity identity across a recreation
 * without comparing raw addresses.
 *
 * All forwarding goes to {@link Probe}; the activities hold no scenario logic.
 */
public abstract class Base extends Activity {
	private static final AtomicInteger COUNTER = new AtomicInteger();
	final String tag = getClass().getSimpleName() + "#" + COUNTER.incrementAndGet();

	private final void ev(String name, String bundle, String phase) {
		Trace t = Probe.trace();
		if (t != null) t.event(tag, name, tag, phase, bundle);
	}

	private Probe app() { return Probe.get(); }

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		ev("onCreate", savedInstanceState == null ? "null" : "nonnull", "pre");
		super.onCreate(savedInstanceState);
		ev("onCreate", savedInstanceState == null ? "null" : "nonnull", "post");
	}

	@Override
	protected void onStart() { ev("onStart", "-", "pre"); super.onStart(); ev("onStart", "-", "post"); }

	@Override
	protected void onRestart() { ev("onRestart", "-", "pre"); super.onRestart(); ev("onRestart", "-", "post"); }

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		ev("onPostCreate", "-", "pre"); super.onPostCreate(savedInstanceState); ev("onPostCreate", "-", "post");
	}

	@Override
	protected void onResume() {
		ev("onResume", "-", "pre");
		super.onResume();
		ev("onResume", "-", "post");
		if (app() != null) app().onResumed(this);
	}

	@Override
	protected void onPause() { ev("onPause", "-", "pre"); super.onPause(); ev("onPause", "-", "post"); }

	@Override
	protected void onStop() { ev("onStop", "-", "pre"); super.onStop(); ev("onStop", "-", "post"); }

	@Override
	protected void onDestroy() {
		ev("onDestroy", "-", "pre");
		super.onDestroy();
		ev("onDestroy", "-", "post");
		if (app() != null) app().onDestroyed(this);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		ev("onSaveInstanceState", "pre", "pre");
		super.onSaveInstanceState(outState);
		ev("onSaveInstanceState", "nonnull", "post");
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		ev("onRestoreInstanceState", "nonnull", "pre");
		super.onRestoreInstanceState(savedInstanceState);
		ev("onRestoreInstanceState", "nonnull", "post");
	}

	@Override
	protected void onNewIntent(Intent intent) {
		ev("onNewIntent", intent == null ? "null" : "nonnull", "pre");
		super.onNewIntent(intent);
		ev("onNewIntent", intent == null ? "null" : "nonnull", "post");
		if (app() != null) app().onNewIntentDelivered(this, intent);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		ev("onActivityResult", "req=" + requestCode + " res=" + resultCode, "pre");
		super.onActivityResult(requestCode, resultCode, data);
		ev("onActivityResult", "req=" + requestCode + " res=" + resultCode, "post");
		if (app() != null) app().onResultDelivered(this, requestCode, resultCode, data);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		ev("onWindowFocusChanged", hasFocus ? "true" : "false", "pre");
	}
}
