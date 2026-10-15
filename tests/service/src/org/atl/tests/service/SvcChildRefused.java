/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S14c: a client running INSIDE a service
 * child must not be able to bind a service in another process.
 *
 * The `child` gate launch runs the loader itself with ATL_SERVICE_PROCESS set,
 * which makes the loader a service child
 * (ATLServiceController.placementRemoteProcess: "Service-to-other-process
 * bindings are not implemented"), regardless of whether a process backend is
 * installed. The refusal is synchronous and must name this component, so the
 * manifest entry below - not the class - is what the row addresses. Nothing here
 * is ever executed: this component must never be placed, never fall back into
 * the loader's process, and never have a callback of its own in the trace.
 */
package org.atl.tests.service;

public class SvcChildRefused extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s14c";

    protected String scenario() {
        return "S14c";
    }
}