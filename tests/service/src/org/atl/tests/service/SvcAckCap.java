/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S13b: the done-executing cap of a started
 * remote component - it is killed six times and must be redelivered six times
 * (flags 0,2,2,2,2,2) and then no more.
 *
 * START_REDELIVER_INTENT is what makes the cap reachable at all: production
 * increments the record's done-executing count once per delivered start under
 * this policy only, and reaching MAX_DONE_EXECUTING_COUNT drops the item, so the
 * row's seventh observation window must stay empty. The kill timings belong to
 * the scenario; the fixture contributes the policy and the per-delivery trace
 * (id, flags, cmd) the row counts.
 */
package org.atl.tests.service;

public class SvcAckCap extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s13b";

    protected String scenario() {
        return "S13b";
    }

    @Override
    protected int startResult() {
        return START_REDELIVER_INTENT;
    }
}