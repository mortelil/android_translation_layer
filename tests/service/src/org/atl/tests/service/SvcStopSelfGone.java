/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S09x: stopSelfResult() when the control link to
 * the policy authority is gone.
 *
 * A hosted child owns no ServiceRecord: the loader decides every stop, and the
 * decision travels back over the child's control link. When that link is unusable -
 * gone, dying, or refusing - the child has no decision to report and
 * Service.stopSelfResult(int) must return false. Returning true there would be the
 * one lie a child can tell on its own: it would report a stop that nothing performed.
 *
 * The arm is driven by the production test seam ATL_SERVICE_PROCESS_FAULT=
 * stopself-broken-link:<process> (ATLProcessControl.faultApplies -> ChildHost.
 * reportStopSelf takes the real no-link path), armed by run.sh for this component's
 * process only, so every other row of that launch keeps a working link.
 *
 * After the refused call the record must still be there, serving the next start id of
 * the same instance (one onCreate): the row's own proof that the loader was never told.
 */
package org.atl.tests.service;

import android.content.Intent;

public class SvcStopSelfGone extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s09x";

    protected String scenario() {
        return "S09x";
    }

    @Override
    protected void onCommand(String cmd, Intent intent, int startId) {
        if ("stopResNow".equals(cmd)) {
            boolean result = stopSelfResult(startId);
            note("stopSelfResult", "startId=" + startId + " ret=" + result
                + " (no control link: the call must return false, never a fabricated stop)");
        }
    }
}
