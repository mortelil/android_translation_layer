/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A05: recreation driven from INSIDE the resume callback. Instance #1 calls
 * recreate() synchronously right after super.onResume(), i.e. while the
 * controller's resume of that very instance is still on the Java stack, so the
 * queued relaunch transaction must not re-enter the in-flight resume. The trace
 * must read resume -> save/destroy of the old instance -> new instance -> resume,
 * never resume -> (re-entrant) teardown -> resume of the same instance.
 *
 * The recreate is issued exactly once: only the first instance (a static
 * counter, the same identity trick Base uses for the tag) recreates, and only on
 * its first resume. A second instance must NOT recreate, otherwise the relaunch
 * would loop forever.
 */
public class ActQ extends Base {
	private static final AtomicInteger INSTANCES = new AtomicInteger();
	private final int instance = INSTANCES.incrementAndGet();
	private boolean recreateIssued;

	@Override
	protected void onResume() {
		super.onResume();
		// super.onResume() already notified the driver, so the driver records
		// this instance as the one whose resume the relaunch must not re-enter.
		if (instance == 1 && !recreateIssued) {
			recreateIssued = true;
			recreate();
		}
	}

	/** Driver-visible identity of the in-callback recreator (instance #1). */
	boolean inCallbackRecreator() { return instance == 1; }
}