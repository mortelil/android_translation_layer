# Bitmap shader image rendering

Build inside Toolbox: `sh tests/bitmap-shader/build.sh build-mobile OUTPUT`.
Sign `OUTPUT/probe.apk` and run `org.atl.bitmapshader/.Probe` on Android
and ATL. Expected: `BITMAP_SHADER_PASS checks=35`. The activity then leaves
an on-screen rounded rectangle and circle containing four coloured quadrants.
No network, accounts, or application data are used.

Checks cover rectangles, rounded rectangles, circles, paths, clipping, copied
local matrices and null reset, translation, canvas scale, CLAMP/REPEAT/MIRROR,
paint alpha, stroke, mutable bitmap replacement, and filtering flags/samples.
Pixel assertions deliberately avoid antialiased boundaries. Inspect the visible
fixture too: bitmap-backed Canvas and the window compositor are separate paths.

Contract reference (no AOSP implementation copied):
https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/graphics/java/android/graphics/BitmapShader.java
and Shader.java in the same directory. Shader coordinates are bitmap pixels;
setLocalMatrix copies the matrix, and null resets it. Paint alpha modulates the
bitmap's alpha; the RGB paint colour does not replace the bitmap.

Scope: affine matrices and equal X/Y tile modes. Mixed X/Y tiling and perspective
remain unsupported and emit a warning without painting a fake solid colour.
Singular/nonfinite matrices draw nothing. This is not complete Shader support:
ComposeShader, anisotropic filtering and newer BitmapShader filter overrides
are outside this patch. Existing blend-mode limitations are unchanged.

The ordinary in-bounds image path uses GSK textures (GTK >= 4.20). Tiling and
older GTK use Cairo sampling; this fallback downloads the source texture, so
large repeated textures may be expensive. Rounded/path masks and colour filters
are applied by the existing GSK drawing path. Search results and playback in
ReVanced are integration checks, not implied by these synthetic tests passing.
