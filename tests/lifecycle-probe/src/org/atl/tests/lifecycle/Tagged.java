/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.app.Activity;

/** A per-instance identity tag, so observer arguments can be matched to the
 * Activity's own log lines without comparing raw object addresses. */
interface Tagged {
    String tag();

    static String tagOf(Activity a) {
        if (a == null) {
            return "null";
        }
        if (a instanceof Tagged) {
            return ((Tagged) a).tag();
        }
        return a.getClass().getSimpleName();
    }
}
