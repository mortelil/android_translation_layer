/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import android.os.Bundle;
import android.widget.TextView;

/**
 * A05/A08/A09: recreation source. Instance #1 calls recreate() from onResume;
 * instance #2 must observe the restored application bundle key "app", the
 * restored view-hierarchy state (TextView text, freezesText=true) and window
 * focus, and must be a distinct Activity object.
 */
public class ActR extends Base {
	private TextView view;
	/** Snapshot for the driver: restored application-bundle value on #2. */
	String restoredApp = "<none>";
	boolean sawSavedBundle;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		view = new TextView(this);
		view.setId(0x7f00ab01);
		view.setFreezesText(true);
		if (savedInstanceState == null) view.setText("typed");
		setContentView(view);
		if (savedInstanceState != null) {
			sawSavedBundle = true;
			restoredApp = String.valueOf(savedInstanceState.getString("app"));
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putString("app", "v1");
		super.onSaveInstanceState(outState);
	}

	String viewText() { return view == null ? "<noview>" : String.valueOf(view.getText()); }

	boolean windowFocused() { return hasWindowFocus(); }
}
