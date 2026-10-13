# Window geometry and configuration overrides

Compile Java/dex in the Alpine build environment:
`CLASSES_ONLY=1 sh tests/window-geometry/build.sh BUILD OUTPUT`.
Package on the host using Android SDK aapt with this directory's manifest/res,
`-I platforms/android-37.0/android.jar`; add OUTPUT/classes.dex and sign.
(The Alpine aapt used during testing aborts when compiling these resource
variants; the script's combined packaging path is retained for working aapt.)

Require WINDOW_GEOMETRY PASS and results.json at default and 2.625 render scale.
The ATL part drives Display.setWindowSize by reflection, checking px/dp,
smallest width, native portrait/landscape resource selection, standalone
Resources isolation, nested width/height overrides, density, fontScale,
locale and night-resource preservation. A real small dialog must not change
the application display dimensions. The portable density/dialog portion also
runs on Android; it skips ATL's private resize entrypoint explicitly.

Derived from OMP OsmAnd 578ff85 geometry work. Configuration's geometry helper
is adapted from AOSP frameworks/base android-15.0.0_r1 ResourcesManager.java,
applyDisplayMetricsToConfiguration (Apache-2.0; existing source header retained).
Unlike the lane registry, only app resources follow the window and explicit
configuration-context overrides are reapplied after geometry changes. Getters
do not silently overwrite configurations. Dialog roots do not publish app size.

Scope is the existing single ATL application window, not multi-display or
Android task/window-manager support. This updates resource tables/metrics but
does not add automatic Activity recreation or onConfigurationChanged dispatch.
