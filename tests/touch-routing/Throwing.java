/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.touchrouting;
public class Throwing extends Visual {
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        throw new IllegalStateException("TOUCH_EXPECTED_EXCEPTION");
    }
}
