# SurfaceView, transparent covers and input

The probe draws a green/magenta SurfaceView below a transparent ColorDrawable
cover, a plain View inflated with `android:alpha="0"`, and an Android Button.
It asserts ColorDrawable alpha modulation (including noncumulative updates and
setColor resetting alpha), opacity, and XML View alpha.

Expected log: `COLOR_ALPHA contracts PASS`, `XML_ALPHA PASS`, and
`SURFACEVIEW_RENDER drew ...`. Click the middle of the green area: the button
must change to `Transparent input responds`, with
`XML_ALPHA transparent click delivered`. Click the button: it must change to
`Controls respond`. The video-colored area must remain visible throughout.
A startup-only pass is insufficient.

Build Java/dex inside atl_dev. Alpine's aapt currently aborts while compiling
this layout; package with the host Android SDK instead (adjust SDK versions):

```sh
toolbox run --container atl_dev sh -lc 'ATL_PROBE_DEX_ONLY=1 sh /home/morten/src/omp-api-review/tests/surfaceview-render/build.sh /home/morten/src/omp-api-review/build-mobile /home/morten/src/revanced-av-20261019/surfaceview-probe'
/home/morten/Android/Sdk/build-tools/36.0.0/aapt package -f \
  -M tests/surfaceview-render/AndroidManifest.xml \
  -S tests/surfaceview-render/res \
  -I /home/morten/Android/Sdk/platforms/android-37.0/android.jar \
  -F /home/morten/src/revanced-av-20261019/surfaceview-probe/surfaceview-render.apk
(cd /home/morten/src/revanced-av-20261019/surfaceview-probe && /home/morten/Android/Sdk/build-tools/36.0.0/aapt add surfaceview-render.apk classes.dex)
ANDROID_APP_DATA_DIR=/home/morten/src/revanced-av-20261019/probe-data \
  /home/morten/src/run-atl-main.sh /home/morten/src/revanced-av-20261019/surfaceview-probe/surfaceview-render.apk
```

The alpha implementation follows AOSP android-17.0.0_r1
`graphics/java/android/graphics/drawable/ColorDrawable.java` (Apache-2.0).
View XML initialization follows AOSP View's `View_alpha` case. Alpha controls
rendering, not whether a visible, enabled View receives input.
