/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S01: start ids and stopSelf(). */
package org.atl.tests.service;

import android.content.Intent;

public class SvcIds extends ProbeService {

    protected String scenario() {
        return "S01";
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