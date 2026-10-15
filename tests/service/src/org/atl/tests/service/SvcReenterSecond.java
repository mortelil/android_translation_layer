/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S07: component bound from inside another binding's onServiceConnected(). */
package org.atl.tests.service;

public class SvcReenterSecond extends ProbeService {

    protected String scenario() {
        return "S07";
    }
}