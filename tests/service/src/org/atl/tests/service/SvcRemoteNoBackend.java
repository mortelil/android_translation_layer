/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S14b: android:process=":remote" component.
 *
 * The current contract - the only one - is a two-armed one:
 *   arm (a) a hosting backend is installed (the real android.atl.ATLProcessHost
 *           in the candidate build, or the fake in a fake launch), so the
 *           placement is SERVED BY A SPAWNED CHILD: the run shows an
 *           ATL_SERVICE_PROCESS_READY line whose pid differs from the loader's,
 *           and this component's callbacks are logged by that child;
 *   arm (b) no hosting backend is installed, so the placement is REFUSED
 *           explicitly with the frozen message
 *           "ATL does not support remote or isolated services: "
 *           + className (ATLServiceController.placementRemoteProcess).
 * Both arms agree on what must NEVER happen: a silent local instance in the
 * loader serving this component. Which arm holds is decided by the launch's env
 * (ATL_PROCESS_BACKEND, or the presence of the real host class) - never by this
 * fixture, which runs wherever production puts it.
 */
package org.atl.tests.service;

public class SvcRemoteNoBackend extends ProbeService {

    protected String scenario() {
        return "S14b";
    }
}
