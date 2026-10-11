/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.jniexc.d;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.atl.tests.jniexc.ProbeJson;
public class ScenarioDActivity extends Activity {
    static final RuntimeException ORIGINAL = new IllegalStateException("native-catch-identity");
    public static class ThrowingActivity extends Activity {
        @Override protected void onCreate(Bundle state) { throw ORIGINAL; }
        @Override protected void onStart() { throw new AssertionError("SENTINEL_D"); }
    }
    @Override protected void onNewIntent(android.content.Intent intent) { throw ORIGINAL; }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Reflection permits compilation against Android's public SDK; the tested
        // method is ATL's actual Java -> native -> Java entry, not a mock.
        try {
            Activity.class.getMethod("nativeStartActivity", Activity.class)
                    .invoke(null, new ThrowingActivity());
            throw new AssertionError("native call did not throw");
        } catch (java.lang.reflect.InvocationTargetException caught) {
            if (caught.getCause() != ORIGINAL) throw new AssertionError("WRONG_IDENTITY");
            System.out.println("JNIEXC_NATIVE_CAUGHT_D");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
            try {
                Activity.class.getMethod("nativeResumeActivity", Class.class, android.content.Intent.class)
                    .invoke(null, ScenarioDActivity.class, new android.content.Intent());
                throw new AssertionError("native resume did not throw");
            } catch (java.lang.reflect.InvocationTargetException caught) {
                if (caught.getCause() != ORIGINAL) throw new AssertionError("WRONG_IDENTITY");
                System.out.println("JNIEXC_RESUME_CAUGHT_D");
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
            String json = ProbeJson.config("D", ProbeJson.instanceId("D"));
            ProbeJson.writeResults(getFilesDir(), json);
            System.out.println("JNIEXC_D_DONE");
            }
        });
    }
}
