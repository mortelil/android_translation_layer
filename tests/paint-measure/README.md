# Paint.measureText result rounding

Android rounds the final public `measureText` result upwards to a whole pixel.
This is separate from native shaping, kerning, hinting and per-glyph metrics.
Reference: AOSP `platform/frameworks/base`, tag `android-16.0.0_r1`,
[Paint.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/graphics/java/android/graphics/Paint.java),
`measureText(char[], int, int)` and `measureText(String, int, int)`.
No AOSP code is copied by this patch.

The same APK checks 64 cases across two families, four fractional/integer text
sizes, ASCII, whitespace, empty strings, combining text, Arabic and supplementary
characters. It asserts finite nonnegative integral widths and agreement between
whole String, String subrange, char[] subrange and CharSequence subrange overloads.
It intentionally does not require system fonts or native advances to match between
Android and Linux. It does not test invalid ranges or compatibility scaling.

Build inside Alpine with Java 8, `dx`, `aapt` and `zipalign`:

```sh
JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
  tests/paint-measure/build.sh "$ANDROID_JAR" /absolute/output
```

Sign `/absolute/output/probe.apk` with a development key using the host Android
SDK's `apksigner`. Install that same signed APK on the Android emulator:

```sh
adb install -r /absolute/output/signed.apk
adb shell am force-stop org.atl.tests.paintmeasure
adb shell am start -W -n org.atl.tests.paintmeasure/.ProbeActivity
adb logcat -d -s System.out:I
```

Run it on ATL inside Alpine with `ATL_BUILD_DIR` explicitly pointing to the
build being tested, its matching `LD_LIBRARY_PATH`, and a fresh
`ANDROID_APP_DATA_DIR`. `scripts/mobile/run.sh` sets the build's working directory
and library path. Select private runtime dependencies with `ATL_PREFIX` and
`ATL_CORE_JAR` as usual. A private Xvfb is sufficient.

Require `PAINT_MEASURE_PASS cases=64`, no `PAINT_MEASURE_FAIL`, and a fresh
process/log. A window, process exit or compiler success is not a pass. Preserve
APK/JAR/native library hashes and process maps to identify the runtime.

Before this fix, unmodified main b3f700ec fails at a 7.25-pixel space with width
2.3046875. Android API 37 and the candidate both pass all 64 cases.
