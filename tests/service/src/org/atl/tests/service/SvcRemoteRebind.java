/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S11d: onUnbind() returns true, so the next
 * bind of the SAME identity must be answered with onRebind() and the cached
 * binder instead of a second onBind() (the keep-alive path the dead-instance
 * part of S04 cannot reach).
 *
 * Same override as the local S04b component (SvcRebindStarted): the rebound
 * instance is only kept when the record survives the unbind, so the row also
 * starts this component before the unbind/rebind pair.
 */
package org.atl.tests.service;

public class SvcRemoteRebind extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s11d";

    protected String scenario() {
        return "S11d";
    }

    @Override
    protected boolean unbindResult() {
        return true;
    }
}