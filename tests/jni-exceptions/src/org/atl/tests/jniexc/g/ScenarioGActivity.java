/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.g;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.atl.tests.jniexc.ProbeJson;
public class ScenarioGActivity extends Activity {
    static class Cyclic extends RuntimeException {
        @Override public synchronized Throwable getCause() { return this; }
    }
    @Override protected void onCreate(Bundle state) {
        final RuntimeException original = new Cyclic();
        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
            if (error != original) throw new AssertionError("WRONG_IDENTITY");
            System.out.println("JNIEXC_IDENTITY_G");
            throw new RuntimeException("handler-failed-G");
            }
        });
        throw original;
    }
}
