/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S13c: the crash cap of a started remote
 * component - it is killed three times and the sticky restarts must stop after
 * the third (no fourth over the closing window).
 *
 * START_STICKY is the mode the sticky-restart half of the row asserts: the
 * restart carries a null intent (ProbeService logs `cmd=none`) with flags 0 and
 * the record's next id, and the restarts keep coming for every start until
 * production's crash count reaches the cap (`ATL_SERVICE_BOUND_CRASH_MAX_RETRY`
 * = 3 in that gate launch). The kill timings and the closing window belong to
 * the scenario; the fixture contributes the policy and the per-restart trace.
 */
package org.atl.tests.service;

public class SvcCrashCap extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s13c";

    protected String scenario() {
        return "S13c";
    }

    @Override
    protected int startResult() {
        return START_STICKY;
    }
}