/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.app.Application;
import android.os.Process;

/**
 * The probe package's Application.
 *
 * <p>Construction is the attach point and onCreate runs after the process' own
 * providers were created (AOSP ActivityThread.handleBindApplication ordering),
 * so a child's inherited stdout carries the P05 evidence: attach, provider
 * onCreate, Application.onCreate, in that order, plus a static-flag assertion
 * that the provider really ran first in this process.
 *
 * <p>With {@code ATL_PROBE_FAKE_READY} set, a child also prints a line that is
 * byte-identical to the child host's READY evidence line before it ever reports
 * ready (P03): a loader that parsed the child's output instead of waiting for
 * the READY transaction would treat such a child as ready.
 */
public class ProbeApp extends Application {
	public ProbeApp() {
		ProbeState.marker("ATL_PROBE_APP_ATTACH");
	}

	public void onCreate() {
		super.onCreate();
		boolean child = ProbeState.processName() != null;
		ProbeState.marker("ATL_PROBE_APP_ONCREATE providers_created=" + ProbeState.providersCreated
		    + " provider_oncreate=" + ProbeState.providerOnCreate);
		if (child && System.getenv("ATL_PROBE_FAKE_READY") != null) {
			System.out.println("ATL_SERVICE_PROCESS_READY pid=" + Process.myPid()
			    + " process=" + ProbeState.processName() + " first=FAKE_PROBE_MARKER");
			ProbeState.marker("ATL_PROBE_FAKE_READY_WRITTEN");
		}
		if (child && ProbeState.providersCreated > 0 && !ProbeState.providerOnCreate)
			ProbeState.marker("ATL_PROBE_CHILD_ORDER_FAIL providers did not run before Application.onCreate");
		System.out.flush();
	}
}
