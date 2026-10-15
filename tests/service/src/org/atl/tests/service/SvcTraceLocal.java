/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S15: the local half of the trace-parity
 * pair. It runs the S02 call sequence in the loader's own process (no
 * android:process in the manifest, hence no PROCESS tag), and its trace must be
 * identical to SvcTraceRemote's up to the component tag - which is why it keeps
 * ProbeService's defaults exactly (binder provided, no rebind, START_NOT_STICKY)
 * and overrides nothing.
 */
package org.atl.tests.service;

public class SvcTraceLocal extends ProbeService {

    protected String scenario() {
        return "S15";
    }
}