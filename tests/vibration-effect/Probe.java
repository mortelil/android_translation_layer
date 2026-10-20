// SPDX-License-Identifier: Apache-2.0
package org.atl.vibrationeffect;
import android.app.Activity;
import android.os.*;

public class Probe extends Activity {
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    static class Recorder extends Vibrator {
        long duration; long[] pattern; int repeat;
        @Override public void vibrate(long duration) { this.duration = duration; }
        @Override public void vibrate(long[] pattern, int repeat) {
            this.pattern = pattern; this.repeat = repeat;
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Recorder v = new Recorder();
            v.vibrate(VibrationEffect.createOneShot(17, VibrationEffect.DEFAULT_AMPLITUDE));
            check(v.duration == 17, "one-shot delegates duration");
            long[] input = {0, 20, 10, 30};
            VibrationEffect effect = VibrationEffect.createWaveform(input, 1);
            input[1] = 999;
            v.vibrate(effect);
            check(v.pattern[1] == 20 && v.repeat == 1, "immutable waveform and repeat");
            v.pattern[1] = 999;
            v.vibrate(effect);
            check(v.pattern[1] == 20, "dispatch does not expose stored array");
            check(!v.hasAmplitudeControl(), "no amplitude control advertised");
            try { VibrationEffect.createOneShot(0, -1); throw new AssertionError("zero duration"); }
            catch (IllegalArgumentException expected) {}
            try { VibrationEffect.createOneShot(5, 0); throw new AssertionError("zero amplitude"); }
            catch (IllegalArgumentException expected) {}
            try { VibrationEffect.createWaveform(new long[]{0, 0}, -1); throw new AssertionError("empty effect"); }
            catch (IllegalArgumentException expected) {}
            try { v.vibrate((VibrationEffect)null); throw new AssertionError("null effect"); }
            catch (NullPointerException expected) {}
            System.out.println("VIBRATION_EFFECT PASS");
            finish(); System.exit(0);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
}
