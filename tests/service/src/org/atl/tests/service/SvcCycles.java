/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S04c: repeated bind/unbind cycles of a purely bound service. */
package org.atl.tests.service;

public class SvcCycles extends ProbeService {

    protected String scenario() {
        return "S04";
    }
}