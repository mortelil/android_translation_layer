# Display/View/Build linkage and defaults

Build inside the container against ANDROID_JAR from an Android SDK:

    ANDROID_JAR=/path/to/android.jar sh tests/display-view-queries/build.sh BUILD_DIR OUTPUT_DIR

Sign probe.apk with a test key and run in an empty profile on ATL or Android.
Require DISPLAY_QUERIES failures=0 and files/results.json with zero failures.
This checks the deprecated display pixel format, default View overlap behavior,
subclass override dispatch, and non-null API-31 SoC metadata fields.

The two-method control failed both sections on pre-fix ATL, and the same APK
passed after adding them. The final probe including SoC fields passes on ATL and
Android. SoC strings come from existing properties with UNKNOWN fallback;
this does not claim that hardware identity has been discovered.

References: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/view/Display.java getPixelFormat(),
core/java/android/view/View.java hasOverlappingRendering(), and
core/java/android/os/Build.java SOC_MANUFACTURER/SOC_MODEL.
