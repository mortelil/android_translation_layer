# PathMeasure contract probe

Build in the ATL container with:

    sh tests/pathmeasure-probe/build.sh BUILD_DIR OUTPUT_DIR

Sign OUTPUT_DIR/pathmeasure-probe.apk with a test key. Run in an empty profile
on ATL or the Android emulator. The host run.sh accepts a signed APK and output
directory and requires explicit probe-runner/runtime environment paths; it checks
the JSON failures, not just the PATHTEST_JSON_END completion marker.

The final probe has 98 assertions: multiple contours, forceClosed, explicit
close followed by lineTo, degenerate paths, nullable output arrays, undersized
arrays, distance clamping/NaN, path replacement and native geometry ownership.
Curve cases cover quadratic, cubic and circular geometry. Circle tests also
check the rightmost starting point, CW/CCW tangents and nonpositive radii.

Review evidence:
- Initial 90-case probe: main's stub fails 72 assertions.
- Extracted OMP implementation: only the added circle case fails, because
  Path.addCircle was itself a stub. Implementing four rational quadratic arcs
  fixes this without approximating the circle with a polygon.
- Final identical signed APK: 98/98 on ATL and Android emulator.

Curve lengths are backend approximations, not bit-exact Skia results. The
initial analytic-length thresholds also failed on Android: Skia reported
14.604048 for a quadratic of analytic length 14.78943, and 62.42697 for a circle
of circumference 62.83185. The final test uses a documented 2% relative bound
for curve lengths; position/tangent and straight-line checks retain their tight
tolerance. The test does not claim every curve or backend is equivalent.

The GC diagnostic is informational, not counted as a passing assertion.
Geometry after Path.reset is an ATL snapshot guarantee; Android documents
modification of the source path after setPath as undefined.

Scope: getLength, getPosTan, nextContour, setPath, and Path.addCircle.
getSegment remains the previous unsupported false-returning method; getMatrix
and isClosed are not implemented. The unrelated ObjectAnimator/TypeConverter
patches are NOT included, and full animation support is not claimed.

References: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
graphics/java/android/graphics/PathMeasure.java; Skia
508606366e71a3daa933a4e08bc49b84e387ec18,
src/core/SkPathMeasure.cpp and SkContourMeasure.cpp (android-17.0.0_r1).
The Java source retains the AOSP Apache-2.0 notice. The GSK backend is ATL code.
