/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S11c: the remote onBind() returns null, so
 * the client must get onNullBinding() and never onServiceConnected().
 *
 * Same override as the local S04a component (SvcNullBind): the service-side
 * onBind() is recorded by ProbeService either way, so the row can distinguish
 * "the child answered with null" from "the child was never asked".
 */
package org.atl.tests.service;

public class SvcRemoteNullBind extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s11c";

    protected String scenario() {
        return "S11c";
    }

    @Override
    protected boolean provideBinder() {
        return false;
    }
}