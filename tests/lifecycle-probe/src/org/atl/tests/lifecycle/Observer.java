/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * LIFE-006 cross-cutting observer. One instance per logical observer id. Every
 * callback records the observer id, the event name, the Activity argument's
 * per-instance tag, Bundle null-ness, the executing thread name and the
 * main-Looper flag.
 *
 * v2 adds three optional, explicitly configured behaviours so that
 * mutation-during-dispatch, exception propagation and equals-based removal can
 * be exercised from inside a real callback:
 *   - {@code actionEvent}/{@code action}/{@code actionName}: on the named event,
 *     run the action then log the named action event (both attributed to this
 *     observer, which is the caller).
 *   - {@code throwOn}: on the named event, log the callback and then throw.
 *   - equality is identity unless a subclass (EqualsObserver) overrides it.
 */
class Observer implements Application.ActivityLifecycleCallbacks {
    private final String id;
    String actionEvent;
    Runnable action;
    String actionName;
    String throwOn;

    Observer(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    private void log(String event, Activity a, String bundle) {
        Trace t = ProbeApp.trace();
        if (t == null) {
            return;
        }
        t.event(id, event, Tagged.tagOf(a), bundle, "-");
    }

    private void logAction(String event) {
        Trace t = ProbeApp.trace();
        if (t != null) {
            t.event(id, event, "-", "-", "-");
        }
    }

    private void after(String event) {
        if (actionEvent != null && actionEvent.equals(event) && action != null) {
            Runnable r = action;
            action = null; // one-shot: the configured mutation fires once
            try {
                r.run();
            } catch (Throwable e) {
                Trace t = ProbeApp.trace();
                if (t != null) {
                    t.recordException("action " + actionName + " failed: " + e);
                }
            }
            if (actionName != null) {
                logAction(actionName);
            }
        }
        if (throwOn != null && throwOn.equals(event)) {
            throw new IllegalStateException("probe-throw:" + id + ":" + event);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        log("onActivityCreated", activity, savedInstanceState == null ? "null" : "nonnull");
        after("onActivityCreated");
    }

    @Override
    public void onActivityStarted(Activity activity) {
        log("onActivityStarted", activity, "-");
        after("onActivityStarted");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        log("onActivityResumed", activity, "-");
        after("onActivityResumed");
    }

    @Override
    public void onActivityPaused(Activity activity) {
        log("onActivityPaused", activity, "-");
        after("onActivityPaused");
    }

    @Override
    public void onActivityStopped(Activity activity) {
        log("onActivityStopped", activity, "-");
        after("onActivityStopped");
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        log("onActivitySaveInstanceState", activity, outState == null ? "null" : "nonnull");
        after("onActivitySaveInstanceState");
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        log("onActivityDestroyed", activity, "-");
        after("onActivityDestroyed");
    }
}
