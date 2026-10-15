/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S04a: onBind() returns null -> client gets onNullBinding(), never onServiceConnected(). */
package org.atl.tests.service;

public class SvcNullBind extends ProbeService {

    protected String scenario() {
        return "S04";
    }

    @Override
    protected boolean provideBinder() {
        return false;
    }
}