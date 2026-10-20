# ViewGroup drawChild dispatch

Build with `sh tests/viewgroup-draw-child/build.sh BUILD_DIR OUTPUT_DIR` inside
Toolbox. Run `probe.apk` with the isolated ATL probe runner, then sign and run
the same APK on Android. Success is `DRAW_CHILD_PASS checks=8` and results.json.

The probe checks virtual drawChild dispatch, replaced pixels, invisible-child
filtering, stable Z order, custom child drawing order and its pixels, and bringChildToFront. It does not call the native
child renderer: actual GTK transforms, scrolling, clipping and invalidation
need separate integration coverage. Passing this probe alone is not enough
for merging a dispatchDraw change.

Reference: AOSP android-15.0.0_r1 ViewGroup.dispatchDraw/buildOrderedChildList.
The test is original code; no application code is included.
