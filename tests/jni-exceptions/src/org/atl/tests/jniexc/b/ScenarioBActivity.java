/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.b;

import android.app.Activity;
import android.os.Bundle;

import java.io.PrintStream;
import java.io.PrintWriter;

import org.atl.tests.jniexc.ProbeJson;

/**
 * JNI-EXC-001 scenario B (formatter-throws variant, EXPERIMENTAL).
 *
 * Same throw as scenario A, but the Throwable subclass overrides every
 * printStackTrace form to throw. ExceptionDescribe() calls printStackTrace(),
 * so this exercises the "the diagnostic itself fails" path (prompt case 4).
 * Expected (contract): the original Throwable still reaches the terminal path
 * with type/message preserved and the formatter failure reported without
 * replacing the original.
 *
 * This variant is marked experimental: its exact ART/CheckJNI behaviour is
 * recorded from the run rather than asserted as a fixed golden.
 */
public class ScenarioBActivity extends Activity {

    /** IllegalStateException whose own formatter throws. */
    public static final class HostileThrowable extends IllegalStateException {
        public HostileThrowable(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public void printStackTrace() {
            throw new RuntimeException("JNI-EXC-001-B formatter failure");
        }

        @Override
        public void printStackTrace(PrintStream s) {
            throw new RuntimeException("JNI-EXC-001-B formatter failure");
        }

        @Override
        public void printStackTrace(PrintWriter s) {
            throw new RuntimeException("JNI-EXC-001-B formatter failure");
        }
    }

    private void sentinel(String where) {
        System.out.println(ProbeJson.SENTINEL_B + " " + where);
        System.out.flush();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ProbeJson.emit("B", ProbeJson.instanceId("B"));
        final Throwable original = new HostileThrowable("JNI-EXC-001-A",
                new UnsupportedOperationException("origin-A"));
        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
            if (error != original) throw new AssertionError("WRONG_IDENTITY");
            System.out.println("JNIEXC_IDENTITY_B");
            error.printStackTrace();
            }
        });
        throw (HostileThrowable) original;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        sentinel("onPostCreate");
    }

    @Override
    protected void onStart() {
        sentinel("onStart");
    }

    @Override
    protected void onResume() {
        sentinel("onResume");
    }
}
