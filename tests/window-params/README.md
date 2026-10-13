# Window parameter value contract

Build in Alpine with `sh tests/window-params/build.sh BUILD_DIR OUTPUT_DIR`.
Sign the APK with a local test key when the runner requires a verified APK.
Run `org.atl.windowparams/.Probe`; require `WINDOW_PARAMS PASS` and no FAIL.
The activity writes `files/results.json` and remains alive for the runner.
The same APK runs on Android. `ANDROID_JAR` optionally selects a SDK jar.

Tests constructors/defaults, all modeled value groups, copy change masks,
idempotence/self-copy, token/package preservation, title snapshot and clearing.
Bit values are literal so the probe does not depend on hidden SDK constants.
AOSP reference: frameworks/base android-15.0.0_r1,
core/java/android/view/WindowManager.java. Implementation originated in OMP's
Sketchbook candidate f81a24b, repaired during integration (constructor fields,
display-mode copying, title handling). It does not claim full Android window
attributes, PiP/display-mode switching or actual window placement support.
