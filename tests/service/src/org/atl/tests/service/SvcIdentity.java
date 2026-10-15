/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S03: one binding identity per Intent.filterEquals (action, not extras). */
package org.atl.tests.service;

public class SvcIdentity extends ProbeService {

    protected String scenario() {
        return "S03";
    }
}