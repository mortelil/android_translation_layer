# Canvas rectangle subtraction

Build in Alpine: `sh tests/canvas-clip-out-rect/build.sh build-mobile OUT`.
Run OUT/probe.apk in a private ATL profile; sign and run the same APK on Android.
Expected marker: `CLIP_OUT_RECT_PASS checks=20` (tag ClipOutRectProbe).
The probe writes results.json in its private files directory.

Checks compare rendered pixels for all four overloads, save/restore, translation
and scale, intersection with an existing rectangular clip, full subtraction,
zero-width and reversed-coordinate rectangles. Android normalizes reversed
rectangle coordinates. The onscreen view is an additional visual fixture; a
black Xvfb screenshot alone is not a successful visual check.

Reference: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
graphics/java/android/graphics/Canvas.java, clipOutRect overloads. These use
DIFFERENCE in local coordinates. This implementation is original ATL code and
delegates to its existing GSK path subtraction; no application-specific case.

Known limitation: the boolean result inherits clipOutPath's conservative true
value; it is not an exact clip-emptiness query. Pixel subtraction, including an
entirely removed area, is tested separately. This patch does not claim complete
Canvas clipping/query support, nor does it solve ReVanced's black player or audio.
