/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.svc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * SVC-001 driver: startService -> onCreate/onStartCommand -> stopService ->
 * onDestroy -> stopService again -> startService to an unknown component.
 *
 * The Activity is the MAIN/LAUNCHER entry point and is kept alive for the whole
 * run; the scenario is driven from the main thread in discrete, settled steps so
 * Android and ATL both get the chance to dispatch the asynchronous Service
 * callbacks between client calls. The ordered event log is emitted by
 * {@link EventLog} and the completion marker is SVC001_JSON_END.
 *
 * All calls are made without intent extras, so Android and ATL launch/behave
 * identically. The probe never self-exits (ATL GUI apps do not); completion is
 * the marker, not process exit.
 */
public class ControlActivity extends Activity {
    /** Delay between client steps, to let async Service callbacks land. */
    static final long SETTLE_MS = 700L;

    private static final String COMP_A = EventLog.PKG + "/.SvcA";
    private static final String COMP_UNKNOWN = EventLog.PKG + "/.SvcUnknown";
    private static final String START_ACTION = "org.atl.tests.svc.START_A";

    private EventLog log;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep a visible window so ATL does not consider the app gone.
        TextView tv = new TextView(this);
        tv.setText("ATL LOCAL-SERVICES probe v1 (SVC-001) running");
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(tv);

        log = EventLog.init(this, EventLog.PROBE_ID);
        handler.postDelayed(stepStartA, 500L);
    }

    /** Step 1: startService(A) — log the returned ComponentName. */
    private final Runnable stepStartA = new Runnable() {
        @Override
        public void run() {
            Intent i = new Intent();
            i.setComponent(new ComponentName(EventLog.PKG, EventLog.PKG + ".SvcA"));
            i.setAction(START_ACTION);
            String ret;
            String retType;
            String exc = null;
            try {
                ComponentName cn = startService(i);
                ret = (cn == null) ? "null" : cn.flattenToShortString();
                retType = (cn == null) ? "null" : "ComponentName";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException("startService(A): " + t);
            }
            log.clientEvent("startService", COMP_A, ret, retType, exc);
            handler.postDelayed(stepStopA1, SETTLE_MS);
        }
    };

    /** Step 2: stopService(A) after onCreate/onStartCommand have been logged. */
    private final Runnable stepStopA1 = new Runnable() {
        @Override
        public void run() {
            logStop("stopService#1", stepStopA2);
        }
    };

    /** Step 3: stopService(A) a second time (service no longer running). */
    private final Runnable stepStopA2 = new Runnable() {
        @Override
        public void run() {
            logStop("stopService#2", stepUnknown);
        }
    };

    /** Step 4: startService to a component that is NOT declared (must not crash). */
    private final Runnable stepUnknown = new Runnable() {
        @Override
        public void run() {
            Intent i = new Intent();
            i.setComponent(new ComponentName(EventLog.PKG, EventLog.PKG + ".SvcUnknown"));
            String ret;
            String retType;
            String exc = null;
            try {
                ComponentName cn = startService(i);
                ret = (cn == null) ? "null" : cn.flattenToShortString();
                retType = (cn == null) ? "null" : "ComponentName";
            } catch (Throwable t) {
                ret = "exception:" + t.getClass().getName();
                retType = "exception";
                exc = t.getClass().getName();
                log.noteException("startService(unknown): " + t);
            }
            log.clientEvent("startService", COMP_UNKNOWN, ret, retType, exc);
            handler.postDelayed(stepFinalize, SETTLE_MS);
        }
    };

    /** Step 5: emit the aggregate results.json + completion markers; stay alive. */
    private final Runnable stepFinalize = new Runnable() {
        @Override
        public void run() {
            log.finalizeRun();
        }
    };

    private void logStop(String label, Runnable next) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(EventLog.PKG, EventLog.PKG + ".SvcA"));
        i.setAction(START_ACTION);
        String ret;
        String retType;
        String exc = null;
        try {
            boolean b = stopService(i);
            ret = Boolean.toString(b);
            retType = "boolean";
        } catch (Throwable t) {
            ret = "exception:" + t.getClass().getName();
            retType = "exception";
            exc = t.getClass().getName();
            log.noteException(label + ": " + t);
        }
        log.clientEvent("stopService", COMP_A, ret, retType, exc);
        handler.postDelayed(next, SETTLE_MS);
    }
}
