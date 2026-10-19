# GradientDrawable color query

Build in Alpine: `sh tests/gradient-colors-query/build.sh build-mobile OUT`.
Run OUT/probe.apk with a private ATL profile; sign/install the same APK on Android.
Expected: `GRADIENT_COLORS_PASS checks=8` (tag GradientColorsProbe).

Verified on Android emulator and ATL x86_64: default solid/null behavior,
constructor and setter colors, detached query arrays, query mutation isolation,
mutated constant-state clone isolation, and clearing with solid color or null.

Reference: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
graphics/java/android/graphics/drawable/GradientDrawable.java#getColors.
ATL already stores integer gradient colors; return a copy rather than exposing
that array. No gradient rendering or media/TLS behavior is changed.
