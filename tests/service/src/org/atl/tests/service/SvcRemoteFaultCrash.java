/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S11b arm 2: the hosting process dies between
 * exec and its first transaction.
 *
 * The fault is armed at runtime by the scenario through
 * ATLFakeProcessBackend.armProcessFault("crash-before-ready:" + getPackageName()
 * + SvcRemoteFaultCrash.PROCESS), one-shot and matching this process only, so no
 * env var of another row can consume it. The binding op is never answered and the
 * real death notice arrives instead, which is the difference from arm 3 (stall,
 * where the process stays up and the op stays unanswered forever).
 *
 * Nothing in this component may run (the instance was never created): any
 * `SvcRemoteFaultCrash.` callback in the trace is the silent fallback the row
 * must refuse.
 */
package org.atl.tests.service;

public class SvcRemoteFaultCrash extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s11bc";

    protected String scenario() {
        return "S11b";
    }
}