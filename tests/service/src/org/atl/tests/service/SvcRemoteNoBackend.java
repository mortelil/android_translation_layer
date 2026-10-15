/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S14b: android:process=":remote" component with
 * no process backend installed (env ATL_PROCESS_BACKEND unset, the production default).
 *
 * Placement must be refused explicitly - never a silent fallback into the caller's
 * process: ATLServiceController.resolvePlacement -> placementRemoteProcess throws
 * UnsupportedOperationException("ATL does not support remote or isolated services: "
 * + className). Nothing here is expected to be executed.
 */
package org.atl.tests.service;

public class SvcRemoteNoBackend extends ProbeService {

    protected String scenario() {
        return "S14b";
    }
}