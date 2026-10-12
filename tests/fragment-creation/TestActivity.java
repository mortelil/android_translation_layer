// SPDX-License-Identifier: GPL-3.0-only
package org.atl.fragmentcreation;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.os.Bundle;
import android.os.Handler;

public class TestActivity extends Activity {
    boolean createReturned;
    final Observer early = new Observer(), queued = new Observer();

    public static class Observer extends Fragment {
        int creates, activityCreates;
        @Override public void onCreate(Bundle state) { super.onCreate(state); creates++; }
        @Override public void onActivityCreated(Bundle state) {
            super.onActivityCreated(state);
            if (!((TestActivity) getActivity()).createReturned)
                throw new AssertionError("activity-created inside Activity.onCreate");
            if (creates != 1) throw new AssertionError("Missing or duplicate create");
            activityCreates++;
        }
        void check() {
            if (creates != 1 || activityCreates != 1)
                throw new AssertionError("creation counts " + creates + "/" + activityCreates);
        }
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FragmentManager manager = getFragmentManager();
        if (manager != getFragmentManager()) throw new AssertionError("Manager identity changed");
        manager.beginTransaction().add(early, "early").commit();
        if (!manager.executePendingTransactions()) throw new AssertionError("No pending work");
        if (early.creates != 1 || early.activityCreates != 0)
            throw new AssertionError("Wrong creation state before override returns");
        manager.beginTransaction().add(queued, "queued").commit();
        createReturned = true;
    }
    @Override protected void onStart() {
        early.check(); queued.check();
        super.onStart();
    }
    @Override protected void onResume() {
        super.onResume();
        new Handler().post(new Runnable() { public void run() {
            Observer late = new Observer();
            getFragmentManager().beginTransaction().add(late, "late").commit();
            if (!getFragmentManager().executePendingTransactions()) throw new AssertionError("No late work");
            late.check(); early.check(); queued.check();
            if (getFragmentManager().executePendingTransactions()) throw new AssertionError("Duplicate transaction");
            late.check();
            System.out.println("FRAGMENT_CREATION_PASS early queued late exactly-once");
            finish();
        }});
    }
}
