# Shared Android line geometry

OMP's Sketchbook lane identified the line-spacing contract and Pango's
half-leading mismatch (c83a2c5 / 67ef640). Its proposed line-height/rise attributes
could report the expected dimensions while placing hard-break glyphs wrongly,
and the shrinking-line tests accepted a Pango clamp instead of Android overlap.
That implementation is not integrated. This replacement keeps Pango shaping
unchanged and computes a shared table of actual line positions.

Reference: AOSP `StaticLayout.out()` at frameworks/base commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` (`android-17.0.0_r1`): extra space is
signed-rounded from `(descent-ascent)*(multiplier-1)+add`, below every line except
the last. Pango's `Layout.set_line_spacing` instead positions baselines using
the following line's height and ignores additive spacing when its factor is
nonzero; absolute line-height attributes also change logical extents. Neither
is a direct implementation of this Android rule.

`AndroidLineGeometry` supplies measurement, baselines, descents, vertical hit
testing and actual snapshot positions. Cache invalidation follows Pango's layout
serial and spacing changes. `AndroidLineBox` retains GtkLabel for text, fonts,
attributes and shaping, delegates default spacing unchanged, and owns the
custom-spacing measurement/snapshot. AlertDialog unwraps this adapter when
accessing the underlying label. StaticLayout.Builder applies its spacing and
uses Android's default multiplier of 1.

The existing GtkText editor remains single-line: setting line spacing records
the property but does not add space after its only visual line. This does not
implement multiline editing, includeFontPadding, full alignment/ellipsis,
selection painting, CSS text shadows during custom-spacing snapshots, or exact
Android font rasterization. Existing font selection and shaping differences
remain. Arbitrary nonfinite spacing is rejected; extreme coordinates saturate
the signed integer geometry rather than overflowing native arithmetic.

## Tests

Inside Alpine:

```sh
sh tests/text-line-spacing/run.sh /absolute/native-test-output
sh tests/text-line-spacing/build.sh /absolute/atl-build /absolute/apk-output
```

The native suite observes actual painted rows and extents, including the widget
snapshot. It covers hard and soft breaks, mixed sizes, trailing empty lines,
negative spacing and a 0.5 multiplier, last-line exclusion, property changes and
text replacement. It does not accept the former half-leading/clamping deviation.

`tests/mobile-smoke/TestLineSpacing.java` checks the public contract, builder,
widget measurements, vertical hit testing and single-line editors. It runs in
the complete mobile-smoke suite. The separate APK runs that same source on
Android: align/sign `probe.apk` with a debug key, install `org.atl.linespacing`,
and launch `.Probe`. Require `LINE_SPACING_REFERENCE PASS` in logcat.

Integration (2026-10-14): native pixel/widget tests, Android API-37 reference and
mobile-smoke pass. Sketchbook reaches its welcome dialog; the discovered adapter
cast in AlertDialog was corrected. Accepting that dialog then reaches the existing
missing `AbsSeekBar.setSplitTrack(boolean)` in `SKBSlider`. The method is absent
on the baseline too; this patch does not implement drawable-backed sliders.
Full Sketchbook drawing/export is not claimed.

Sources: https://docs.gtk.org/Pango/method.Layout.set_line_spacing.html and
https://docs.gtk.org/Pango/func.attr_line_height_new_absolute.html.
