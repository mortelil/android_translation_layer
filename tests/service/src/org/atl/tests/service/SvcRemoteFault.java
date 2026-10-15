/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S11b arm 1: the hosting process is never
 * spawned at all.
 *
 * The fault is armed outside this class - the `fault` gate launch exports
 * ATL_SERVICE_PROCESS_FAULT=spawn, so the next spawn of any process fails - and
 * the row asserts the CLIENT-side outcome (the undelivered bind is told the host
 * is unavailable and never gets a connect). Nothing in this component may run:
 * the process has no instance, so every `SvcRemoteFault.` callback in the trace
 * is evidence of a silent fallback into the loader's own process, which is
 * exactly what Gate.expectNeverRan must catch.
 */
package org.atl.tests.service;

public class SvcRemoteFault extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s11b";

    protected String scenario() {
        return "S11b";
    }
}