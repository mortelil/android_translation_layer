# TransitionDrawable contract probe

Tests XML inflation and constant-state type, first/second layer pixels,
forward/reverse/zero-duration transitions, reset, and cross-fade configuration.
The implementation is adapted from AOSP `android-10.0.0_r1`,
`frameworks/base/graphics/java/android/graphics/drawable/TransitionDrawable.java`.
Source commit: `57bb140be9e48cf08acba131f7463e461777bb8e` (Apache-2.0).

Build Java/dex in `atl_dev` using `ATL_TEST_CLASSES_ONLY=1 sh build.sh BUILD OUT`.
Package on the host with SDK `aapt package -f -M AndroidManifest.xml -S res
-I "$ANDROID_JAR" -F "$OUT/probe.apk"`, then add `$OUT/classes.dex` with
`aapt add` from OUT. (Alpine aapt currently aborts while packaging resources.)
Run the APK inside atl_dev with the intended `ATL_BUILD_DIR`, `ATL_PREFIX`,
`ATL_CORE_JAR`, and a private `ANDROID_APP_DATA_DIR`. Success requires
`TRANSITION_DRAWABLE PASS` and exit 0, not merely successful startup.
