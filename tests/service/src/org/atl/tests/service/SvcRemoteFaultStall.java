/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S11b arm 3: the hosting process lives but
 * never reports ready, so the binding op is never answered.
 *
 * The fault is armed at runtime by the scenario through
 * ATLFakeProcessBackend.armProcessFault("stall-before-ready:" + getPackageName()
 * + SvcRemoteFaultStall.PROCESS), one-shot and matching this process only. The
 * difference from arm 2 is the absence of a death notice: the row asserts that
 * production neither invents a callback nor silently falls back to the loader's
 * process while the op is outstanding.
 *
 * Nothing in this component may run.
 */
package org.atl.tests.service;

public class SvcRemoteFaultStall extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s11bs";

    protected String scenario() {
        return "S11b";
    }
}