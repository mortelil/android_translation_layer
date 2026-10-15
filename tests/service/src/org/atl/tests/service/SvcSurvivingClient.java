/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S10: the restart policy of a STARTED remote
 * component across a process death, with the client still bound (arm 1) or gone
 * (arm 2).
 *
 * The fixture's whole contribution is the start mode: START_STICKY is what makes
 * production restart the STARTED record after the kill, and because a sticky
 * restart has no start item to replay, it carries a NULL intent - ProbeService
 * logs that as `cmd=none`, on a fresh instance (onCreate 2) and a fresh id, which
 * is the observable the row asserts. Which of the two arms runs (client stays
 * bound / client unbinds first) is decided by the scenario, not here.
 *
 * The second arm (realhost gate) has no kill seam at all: the row sends
 * `cmd=die` and this fixture exits its own process instead. That is a REAL death
 * - ATLProcessHost.startReaper reaps the child by pid (ATLBinderConnection.reap)
 * and always tears the entry down with childDied=true, which fires the
 * production ProcessDeathListener and the registry's restart policy - so the row
 * observes a dying process, never a synthesized callback. The exit is delayed by
 * 250 ms so the reply of the START transaction that carried `cmd=die` reaches
 * the loader before the socket goes away; a worker thread does the sleeping
 * because onStartCommand() must return first.
 */
package org.atl.tests.service;

import android.content.Intent;

public class SvcSurvivingClient extends ProbeService {

    /** The manifest's android:process tag; the process name is getPackageName() + PROCESS. */
    public static final String PROCESS = ":s10";

    protected String scenario() {
        return "S10";
    }

    @Override
    protected int startResult() {
        return START_STICKY;
    }

    @Override
    protected void onCommand(String cmd, Intent intent, int startId) {
        if ("die".equals(cmd)) {
            note("die", "this process exits itself (a real death; the loader has no kill seam)");
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        // the exit below is due either way
                    }
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }, "S10-die");
            t.setDaemon(true);
            t.start();
        }
    }
}
