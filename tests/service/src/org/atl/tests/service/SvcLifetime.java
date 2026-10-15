/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S02: started + bound lifetime across stopService(). */
package org.atl.tests.service;

public class SvcLifetime extends ProbeService {

    protected String scenario() {
        return "S02";
    }
}