/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S10: the restart policy of a STARTED remote
 * component across a process death, with the client still bound (arm 1) or gone
 * (arm 2).
 *
 * The fixture's whole contribution is the start mode: START_STICKY is what makes
 * production restart the STARTED record after the kill, and because a sticky
 * restart has no start item to replay, it carries a NULL intent - ProbeService
 * logs that as `cmd=none`, on a fresh instance (onCreate 2) and a fresh id, which
 * is the observable the row asserts. Which of the two arms runs (client stays
 * bound / client unbinds first) is decided by the scenario, not here.
 */
package org.atl.tests.service;

public class SvcSurvivingClient extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s10";

    protected String scenario() {
        return "S10";
    }

    @Override
    protected int startResult() {
        return START_STICKY;
    }
}