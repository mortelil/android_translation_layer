/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S04c (SVC-008): repeated bind/unbind cycles
 * of a purely bound service whose onUnbind() returns true.
 *
 * The instance is not started, so the last unbind is the last client: production
 * must destroy the instance even though onUnbind() asked to keep it, which is
 * what the next bind proves - a NEW instance (new onCreate/onBind, different
 * binder, no onRebind) instead of a rebind of the old one.
 */
package org.atl.tests.service;

public class SvcCycles extends ProbeService {

    protected String scenario() {
        return "S04";
    }

    @Override
    protected boolean unbindResult() {
        return true;
    }
}
