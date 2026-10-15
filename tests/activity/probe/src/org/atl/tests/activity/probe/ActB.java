/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import android.app.Activity;
import android.content.Intent;

/**
 * Secondary Activity (declared singleTop): driven for the A-&gt;B-&gt;A result
 * round trip and for the new-intent delivery to an existing instance.
 */
public class ActB extends Base {
	/** AOSP result contract: setResult then finish (Activity.setResult). */
	void deliverResult() {
		Intent data = new Intent();
		data.putExtra("nav", "ok");
		setResult(Activity.RESULT_OK, data);
		finish();
	}

	String recordIntentExtra() {
		Intent i = getIntent();
		return i == null ? "<null>" : String.valueOf(i.getStringExtra("req"));
	}
}
