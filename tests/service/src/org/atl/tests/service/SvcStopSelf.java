/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S09: a REMOTE component's stopSelf() and
 * stopSelf(int). The two arms are exactly S01's (SvcIds), so the local and the
 * remote row assert the same start-id rule on the same extra contract:
 *
 *   cmd=stopOld + int extra oldId  stopSelf(olderId) - the older id must be
 *                                  refused (`startId >= 0 && lastStartId !=
 *                                  startId`), the record stays alive
 *   cmd=stopNow                    stopSelf() of the current start - must stop
 *
 * The call is made from inside the hosting process' Service instance, so it
 * exercises the child-originated route (Service.stopSelf -> the instance's
 * record) rather than the client's stopService().
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
        }
    }
}