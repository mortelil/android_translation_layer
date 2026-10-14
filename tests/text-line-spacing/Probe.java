/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.linespacing;
public class Probe extends android.app.Activity {
	@Override public void onCreate(android.os.Bundle state) {
		super.onCreate(state);
		try {
			Class.forName("TestLineSpacing").getMethod("run",android.content.Context.class).invoke(null,this);
			System.out.println("LINE_SPACING_REFERENCE PASS");
		} catch (Throwable error) { error.printStackTrace(); System.out.println("LINE_SPACING_REFERENCE FAIL"); }
		finish();
	}
}
