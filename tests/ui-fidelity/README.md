# UI fidelity integration checks

The October 2026 candidate retains bundled font loading, synthetic bold/oblique,
outline clipping, TextView alignment and Roboto selection when installed.
Integration repairs cover three shared contracts:

- PangoRenderer dispatches colours, backgrounds, underline/strikethrough,
  shapes and baseline/run offsets. Glyph callbacks add synthetic style without
  replacing that attribute dispatch. Logical bounds retain whitespace backgrounds.
  Colour glyph runs keep normal colour rendering instead of converting to outlines.
- Face-style caching is protected by a mutex and owned by the live PangoFont;
  descriptor paths cannot survive their font and be mistaken for a reused fd.
  Family availability is queried directly, without a stale FcConfig pointer cache.
- Legacy FontFamily validates bytes and collection indices, enforces freeze/abort
  state, copies axes, and retains per-face weight/slant. Faces in one family share
  a native family name so style matching can select another member. Metadata
  tests use original rectangle fonts, not application files or personal text.

Run in Alpine Toolbox, with the normal runtime environment configured:

    sh tests/text-line-spacing/run.sh /tmp/ui-spacing
    sh tests/text-alignment/run.sh /tmp/ui-alignment
    sh tests/text-font-family/run.sh /tmp/ui-family
    sh tests/text-style-concurrency/run.sh /tmp/ui-threads

The outline runner currently runs Toolbox itself; invoke it from the host:

    sh tests/render-node-outline-clip/run.sh

Run the existing mobile-smoke build/run scripts against the rebuilt runtime.
TestLegacyFontFamily is included in TestActivity. It checks corrupt bytes, invalid
TTC indices/weight, asset and buffer entry points, retained buffer position,
style-based face selection, default style inference, freeze/abort, and isolation.

The styled-colour pixel regression fails on OMP commit 0ac070ed's original
AndroidLineGeometry.c (zero red pixels), and passes after the renderer repair.
The original outline test put its rotated figure outside the image; the repaired
fixture translates AFTER rotation while retaining the area/size assertions.

Results (2026-10-16): text geometry 190 checks, alignment 30, font-family 22,
outline checks, concurrency 8 threads/1600 calls, and mobile-smoke all pass.
A concurrency stress pass is not a formal proof or a ThreadSanitizer run.
Roboto-specific tests require an installed Roboto, as documented by their output.

App checks: private fresh profiles showed Rhythm's italic welcome/bold title and
rounded icon buttons, and centred/wrapped AntennaPod empty-state text. Both closed
through the window close event. AntennaPod still logs the pre-existing missing
ObjectAnimator.ofObject overload; its animation is not verified. Rhythm playback
and Nura were not tested. Logs/screenshots remain outside the repository.

The legacy addFontWeightStyle(path, weight, italic) boolean return/signature was
checked against AOSP android-5.1.1_r1 graphics/java/android/graphics/FontFamily.java;
the candidate had mistaken weight for a TTC index.

Scope limitations: no full Android text implementation, RTL contract overhaul,
complete span-to-Pango conversion, full notification support, or mobile-device
verification. Existing rendering approximations (Cairo outline emboldening and
one-pixel hairlines) remain. This work does not claim that every UI mismatch or
Rhythm media-playback failure is fixed. No Signal launch is part of validation.

References: PangoRenderer and PangoCairo APIs (local installed headers),
https://docs.gtk.org/Pango/class.Renderer.html,
https://docs.gtk.org/Pango/method.Renderer.get_alpha.html;
AOSP references for the original UI changes remain in their source and commit
messages. Integration code is independently written for ATL/Pango.
