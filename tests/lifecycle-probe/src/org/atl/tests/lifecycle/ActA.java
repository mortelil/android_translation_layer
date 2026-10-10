/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main launcher Activity. Every lifecycle override logs a pre-super line before
 * {@code super.<method>()} and a post-super line after it, so the callback
 * placement inside super is observable. The per-instance tag (ActA#N) proves
 * Activity identity without comparing raw addresses.
 */
public class ActA extends Activity implements Tagged {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final String tag = "ActA#" + COUNTER.incrementAndGet();

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
        try {
            super.onCreate(savedInstanceState);
        } catch (RuntimeException e) {
            ev("onCreate.caught", "-", "caught");
        }
        ev("onCreate.post", savedInstanceState == null ? "null" : "nonnull", "post");
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
            ProbeApp.get().onActAResumed(this);
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
            ProbeApp.get().onActADestroyed();
        }
    }

    /** Called by the driver; executes on the main thread with ActA resumed. */
    void startB() {
        startActivity(new Intent(this, ActB.class));
    }
}
