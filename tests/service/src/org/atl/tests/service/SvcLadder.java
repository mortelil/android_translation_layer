/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S13a: the measured restart ladder of a
 * remote component - the process is killed three times and the delays between a
 * kill and the next connect must follow the AOSP schedule (first restart after
 * the exact minimum, then the x4 branch), which is why the row measures wall
 * clock deltas with Gate.expectBetween instead of counting callbacks.
 *
 * The component is BOUND ONLY (BIND_AUTO_CREATE, never started). The restarts
 * the row measures are therefore the AUTO_CREATE rebinds of a live binding after
 * the process death of S13a's own process: `connect -> kill -> connect ...`, with
 * a fresh instance per restart (onCreate/onBind 4) and no onStartCommand at all.
 * The fixture contributes exactly that: ProbeService defaults, and enough
 * per-instance callback tags (onCreate/onBind, and the client-side connect with
 * its uptime) to time each restart. A started fixture here would add start-item
 * policy the row does not measure.
 */
package org.atl.tests.service;

public class SvcLadder extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s13a";

    protected String scenario() {
        return "S13a";
    }
}