/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S15: the remote half of the trace-parity
 * pair. Same code as SvcTraceLocal (ProbeService defaults, nothing overridden),
 * placed in its own process by the manifest, so Gate.expectSameTraces can compare
 * the two traces up to the component tag: the same call sequence must produce the
 * same lifecycle callbacks, with the same ids/flags/cmd, whether the instance is
 * hosted in the loader's process or in a child process.
 */
package org.atl.tests.service;

public class SvcTraceRemote extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s15";

    protected String scenario() {
        return "S15";
    }
}