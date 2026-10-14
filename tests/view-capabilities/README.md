# View capabilities

Build inside Toolbox: `sh tests/view-capabilities/build.sh BUILD_DIR OUT_DIR`.
Run the unsigned APK with the candidate runtime; require `VIEW_CAPABILITIES PASS`.
Sign separately for Android installation.

Checks default sound-effects preference, disable/re-enable, per-view isolation,
and the feature implications distinct multitouch -> multitouch -> touchscreen.
ATL already advertises distinct multitouch; reporting the implied base features
prevents VLC 3.7.1 from incorrectly selecting its TV launcher.

Sound-effect preference semantics reference AOSP View.java, android-17.0.0_r1
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d. No source copied. Actual UI sound
playback remains unimplemented; this test does not claim audible click feedback.
