/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S04b: onUnbind() returns true -> the next bind of the same identity gets onRebind(). */
package org.atl.tests.service;

public class SvcRebindStarted extends ProbeService {

    protected String scenario() {
        return "S04";
    }

    @Override
    protected boolean unbindResult() {
        return true;
    }
}