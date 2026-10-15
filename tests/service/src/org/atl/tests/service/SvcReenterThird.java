/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S07: component whose client unbinds itself from inside its own onServiceConnected(). */
package org.atl.tests.service;

public class SvcReenterThird extends ProbeService {

    protected String scenario() {
        return "S07";
    }
}