/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.app.Activity;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Secondary Activity, started by ActA for the A->B->A transition (S2), the
 * LIFE-004 multiplicities (S4.x) and the app-initiated finish (S3D1).
 * Same pre-super/post-super logging contract as ActA.
 */
public class ActB extends Activity implements Tagged {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final String tag = "ActB#" + COUNTER.incrementAndGet();

    @Override
    public String tag() {
        return tag;
    }

    private void ev(String event, String bundle, String phase) {
        Trace t = ProbeApp.trace();
        if (t != null) {
            t.event(tag, event, tag, bundle, phase);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ev("onCreate.pre", savedInstanceState == null ? "null" : "nonnull", "pre");
        boolean threw = false;
        try {
            super.onCreate(savedInstanceState);
        } catch (RuntimeException e) {
            // L-THROW terminal scenario: the Application dispatch does not
            // catch a callback exception, so it escapes super.onCreate and is
            // caught here. On Android the framework then throws
            // SuperNotCalledException (mCalled never set); that is the expected
            // terminal end of the probe, not a probe defect.
            threw = true;
            ev("onCreate.caught", "-", "caught");
        }
        ev("onCreate.post", savedInstanceState == null ? "null" : "nonnull", "post");
        if (threw) {
            ProbeApp app = ProbeApp.get();
            if (app != null) {
                // Reliable completion signal for the terminal scenario: emitted
                // after the prefix (throw observed) and before the framework's
                // mCalled check kills the process.
                app.terminalThrowObserved();
            }
        }
    }

    @Override
    protected void onStart() {
        ev("onStart.pre", "-", "pre");
        super.onStart();
        ev("onStart.post", "-", "post");
    }

    @Override
    protected void onRestart() {
        ev("onRestart.pre", "-", "pre");
        super.onRestart();
        ev("onRestart.post", "-", "post");
    }

    @Override
    protected void onResume() {
        ev("onResume.pre", "-", "pre");
        super.onResume();
        ev("onResume.post", "-", "post");
        if (ProbeApp.get() != null) {
            ProbeApp.get().onActBResumed(this);
        }
    }

    @Override
    protected void onPause() {
        ev("onPause.pre", "-", "pre");
        super.onPause();
        ev("onPause.post", "-", "post");
    }

    @Override
    protected void onStop() {
        ev("onStop.pre", "-", "pre");
        super.onStop();
        ev("onStop.post", "-", "post");
    }

    @Override
    protected void onDestroy() {
        ev("onDestroy.pre", "-", "pre");
        super.onDestroy();
        ev("onDestroy.post", "-", "post");
        if (ProbeApp.get() != null) {
            ProbeApp.get().onActBDestroyed();
        }
    }
}
