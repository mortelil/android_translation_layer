/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import android.content.Intent;

/** Launcher Activity: the stable parent the other scenarios return to. */
public class ActA extends Base {
	/** Explicit-component launch; ATL's startActivityForResult requires it. */
	void startForResult(int requestCode) {
		startActivityForResult(new Intent(this, ActB.class).putExtra("req", "first"), requestCode);
	}

	void startC() { startActivity(new Intent(this, ActC.class)); }

	void startR() { startActivity(new Intent(this, ActR.class).putExtra("app", "v1")); }

	void startB(String req) { startActivity(new Intent(this, ActB.class).putExtra("req", req)); }
}
