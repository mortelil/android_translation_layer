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

Touch focus checks cover the false default, per-view isolation, enabling touch
focus implies explicit FOCUSABLE, disabling touch focus preserves ordinary
focusability, both NOT_FOCUSABLE setters clear touch focus, and FOCUSABLE_AUTO
preserves the touch flag. Semantics reference AOSP View.java,
android-15.0.0_r1, setFocusable/setFocusableInTouchMode/isFocusableInTouchMode:
https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/View.java
No source copied. This does not claim complete touch-mode focus traversal.

Integration regression: Firefox 157.0.1, open a page, menu -> Find in page,
type a word, select next match, close search. Previously the keyboard helper
threw NoSuchMethodError for isFocusableInTouchMode; it now searches and
highlights matches. Tested with an isolated profile, no account.
