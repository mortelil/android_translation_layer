/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import android.os.Bundle;

/**
 * A05: an Activity that calls finish() inside its own onCreate. It must never
 * reach onStart/onResume, must be destroyed exactly once, and must not save
 * instance state (AOSP Activity.finish inside onCreate -&gt; inline teardown,
 * mFinished set before the create transaction unwinds).
 */
public class ActC extends Base {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		finish();
	}
}
