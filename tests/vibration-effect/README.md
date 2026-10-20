# VibrationEffect dispatch

Build in Toolbox with `sh build.sh BUILD OUT`, then run `OUT/probe.apk`
with the intended ATL runtime. Require `VIBRATION_EFFECT PASS` and exit 0.
The probe records dispatch without activating a motor.

Checks one-shot duration, waveform defensive copies and repeat-index forwarding,
invalid arguments, and truthful lack of amplitude-control capability.
This does not verify motor timing, legacy waveform cancellation, or predefined
haptic effects. ATL's legacy motor backend cannot play predefined effects.

Reference: AOSP `android-17.0.0_r1`, `frameworks/base/core/java/android/os/`
`Vibrator.java` and `VibrationEffect.java` (Apache-2.0). Implementation is original.
