/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S07: component whose connect callback issues a re-entrant bindService()/unbindService(). */
package org.atl.tests.service;

public class SvcReenterFirst extends ProbeService {

    protected String scenario() {
        return "S07";
    }
}