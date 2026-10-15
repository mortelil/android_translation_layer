/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S12: a started remote component across
 * process deaths - arm 1 without a client, arm 2 with a bound client whose
 * connection must survive the restart but be told about the death.
 *
 * START_REDELIVER_INTENT is the mode the row needs: it is the only policy under
 * which the acked start item is kept, so the restart redelivers the SAME intent
 * with flags START_FLAG_RETRY|START_FLAG_REDELIVERY (ProbeService logs the id,
 * the flags and the cmd of every delivery), and it is the only one that can
 * reach the done-executing cap. The kill timings are the scenario's: the fixture
 * only has to produce the policy and its per-delivery trace.
 */
package org.atl.tests.service;

public class SvcRedeliver extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s12";

    protected String scenario() {
        return "S12";
    }

    @Override
    protected int startResult() {
        return START_REDELIVER_INTENT;
    }
}