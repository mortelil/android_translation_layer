/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S09: a REMOTE component's stopSelf() and
 * stopSelf(int)/stopSelfResult(int). The two stopSelf arms are exactly S01's
 * (SvcIds), so the local and the remote row assert the same start-id rule on the
 * same extra contract:
 *
 *   cmd=stopOld + int extra oldId  stopSelf(olderId) - the older id must be
 *                                  refused (`startId >= 0 && lastStartId !=
 *                                  startId`), the record stays alive
 *   cmd=stopNow                    stopSelf() of the current start - must stop
 *   cmd=stopResOld + int extra oldId  stopSelfResult(olderId) - the stale start id is
 *                                  refused, so the CALL must return false and nothing
 *                                  may be torn down
 *   cmd=stopResNow                 stopSelfResult(current start id) - the record accepts,
 *                                  so the CALL must return true and the teardown follows
 *
 * The call is made from inside the hosting process' Service instance, so it
 * exercises the child-originated route (Service.stopSelf* -> the owner's decision)
 * rather than the client's stopService(). The stopSelfResult arms pin the RETURN
 * VALUE of that route: a hosted child keeps no record of its own, so the decision
 * has to travel back from the loader over the control link - a child that answered
 * true on its own would report a stop the authority refused (or never heard).
 */
package org.atl.tests.service;

import android.content.Intent;

public class SvcStopSelf extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s09";

    protected String scenario() {
        return "S09";
    }

    @Override
    protected void onCommand(String cmd, Intent intent, int startId) {
        if ("stopOld".equals(cmd)) {
            int older = intent == null ? -1 : intent.getIntExtra("oldId", -1);
            note("stopSelf", "startId=" + older + " (older start id: must not stop the service)");
            stopSelf(older);
        } else if ("stopNow".equals(cmd)) {
            note("stopSelf", "startId=current (unconditional stopSelf() of the current start)");
            stopSelf();
        } else if ("stopResOld".equals(cmd)) {
            int older = intent == null ? -1 : intent.getIntExtra("oldId", -1);
            boolean result = stopSelfResult(older);
            note("stopSelfResult", "startId=" + older + " ret=" + result
                + " (older start id: the record must refuse, the call must return false)");
        } else if ("stopResNow".equals(cmd)) {
            boolean result = stopSelfResult(startId);
            note("stopSelfResult", "startId=" + startId + " ret=" + result
                + " (current start: the record must accept, the call must return true)");
        }
    }
}