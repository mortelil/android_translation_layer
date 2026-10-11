/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.f;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.atl.tests.jniexc.ProbeJson;
public class ScenarioFActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        final RuntimeException original = new IllegalStateException("looper-original");
        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
            if (error != original) throw new AssertionError("WRONG_IDENTITY");
            System.out.println("JNIEXC_IDENTITY_F");
            }
        });
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override public void run() { throw original; } });
        handler.post(new Runnable() {
            @Override public void run() { System.out.println("SENTINEL_F"); }
        });
    }
}
