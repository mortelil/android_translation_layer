/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S02: started + bound lifetime across stopSelf()/stopServiceFromSelf. */
package org.atl.tests.service;

import android.content.Intent;

public class SvcSelfLifetime extends ProbeService {

    protected String scenario() {
        return "S02";
    }

    @Override
    protected void onCommand(String cmd, Intent intent, int startId) {
        if ("selfStop".equals(cmd)) {
            note("stopSelf", "startId=current (stopSelf() from onStartCommand while bound)");
            stopSelf();
        }
    }
}