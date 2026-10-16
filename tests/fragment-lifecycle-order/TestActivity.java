// SPDX-License-Identifier: GPL-3.0-only
package org.atl.fragmentorder;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.os.Bundle;
import android.os.Handler;

public class TestActivity extends Activity {
    private final StringBuilder events = new StringBuilder();
    private boolean startReturned, resumeReturned;
    private void record(String event) { events.append(event).append(';'); }

    public static class ObserverFragment extends Fragment {
        @Override public void onPause() { super.onPause(); ((TestActivity)getActivity()).record("fragment-pause"); }
        @Override public void onStop() { super.onStop(); ((TestActivity)getActivity()).record("fragment-stop"); }
        @Override public void onDestroy() { super.onDestroy(); ((TestActivity)getActivity()).record("fragment-destroy"); }
        @Override public void onStart() {
            super.onStart();
            TestActivity owner = (TestActivity) getActivity();
            if (!owner.startReturned) throw new AssertionError("Fragment started inside Activity.onStart");
            owner.record("fragment-start");
        }
        @Override public void onResume() {
            super.onResume();
            TestActivity owner = (TestActivity) getActivity();
            if (!owner.resumeReturned) throw new AssertionError("Fragment resumed inside Activity.onResume");
            owner.record("fragment-resume");
        }
    }

    @Override protected void onPause() {
        record("activity-pause-before"); super.onPause(); record("activity-pause-after");
    }
    @Override protected void onStop() {
        record("activity-stop-before"); super.onStop(); record("activity-stop-after");
    }
    @Override protected void onDestroy() {
        record("activity-destroy-before"); super.onDestroy(); record("activity-destroy-after");
        String expected = "fragment-pause;activity-pause-before;application-pause;activity-pause-after;"
            + "fragment-stop;activity-stop-before;application-stop;activity-stop-after;"
            + "fragment-destroy;activity-destroy-before;application-destroy;activity-destroy-after;";
        if (!expected.equals(events.toString())) throw new AssertionError(events.toString());
        System.out.println("FRAGMENT_DOWNWARD_ORDER_PASS " + events);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) { record("application-start"); }
            public void onActivityResumed(Activity a) { record("application-resume"); }
            public void onActivityPaused(Activity a) { record("application-pause"); }
            public void onActivityStopped(Activity a) { record("application-stop"); }
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { record("application-destroy"); }
        });
        // Use one manager instance: pending operations belong to that manager in ATL.
        android.app.FragmentManager manager = getFragmentManager();
        manager.beginTransaction().add(new ObserverFragment(), "explicit-observer").commit();
        manager.executePendingTransactions();
    }
    @Override protected void onStart() {
        record("activity-start-before");
        super.onStart();
        record("activity-start-after");
        startReturned = true;
    }
    @Override protected void onResume() {
        record("activity-resume-before");
        super.onResume();
        record("activity-resume-after");
        resumeReturned = true;
        new Handler().post(new Runnable() { public void run() {
            String expected = "activity-start-before;application-start;activity-start-after;fragment-start;"
                + "activity-resume-before;application-resume;activity-resume-after;fragment-resume;";
            if (!expected.equals(events.toString())) throw new AssertionError(events.toString());
            System.out.println("FRAGMENT_ORDER_PASS " + events);
            events.setLength(0);
            finish();
        }});
    }
}
