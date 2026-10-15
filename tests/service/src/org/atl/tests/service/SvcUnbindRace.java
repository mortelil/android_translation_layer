/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S08: bindService() immediately followed by unbindService() in one main-looper tick. */
package org.atl.tests.service;

public class SvcUnbindRace extends ProbeService {

    protected String scenario() {
        return "S08";
    }
}