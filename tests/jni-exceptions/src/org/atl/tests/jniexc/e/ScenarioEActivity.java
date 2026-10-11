/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.e;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.atl.tests.jniexc.ProbeJson;
public class ScenarioEActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        final RuntimeException original = new IllegalStateException("handler-original");
        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
            if (error != original || thread != Thread.currentThread())
                throw new AssertionError("WRONG_IDENTITY");
            System.out.println("JNIEXC_IDENTITY_E");
            }
        });
        throw original;
    }
    @Override protected void onStart() { System.out.println("SENTINEL_E"); }
}
