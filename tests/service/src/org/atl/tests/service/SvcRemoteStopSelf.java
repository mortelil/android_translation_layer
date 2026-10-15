/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S11a: a child-originated stopSelf() must be
 * routed to the authoritative record, not decided by the hosting side.
 *
 * The arms reuse S01's/S09's extra contract verbatim (cmd=stopOld + int extra
 * oldId, cmd=stopNow), so the row can assert both halves of the authority:
 * stopSelf() from inside the process tears the record down, and stopSelf() with
 * an id that is not the record's current last start id is refused by the record
 * (`startId >= 0 && lastStartId != startId`) - the child cannot stop a record
 * whose start ids it does not own.
 */
package org.atl.tests.service;

import android.content.Intent;

public class SvcRemoteStopSelf extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s11a";

    protected String scenario() {
        return "S11a";
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