# PaintDrawable contract

Build in atl_dev: `sh tests/paint-drawable/build.sh build-mobile OUTPUT`.
Sign the APK and run org.atl.paintdrawable/.Probe on Android and ATL.
Expected: PAINT_DRAWABLE_PASS checks=14.

The same APK checks constructor color, filled pixels, rounded and individual
corners, clearing radii, short-array rejection and alpha. Pixel checks avoid
antialiased edge boundaries. XML corners inflation is imported from AOSP but
is not covered by this probe.

PaintDrawable.java is copied from AOSP frameworks/base android-17.0.0_r1,
commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, preserving its Apache-2.0
header. Only the unavailable Ravenwood test annotation was removed.

Also checks default/set/reset of TextView private IME options, used by the
ReVanced search field. These are retained as opaque metadata; GTK keyboards
do not interpret Android vendor-specific options. This does not add Android
InputMethodService integration.
