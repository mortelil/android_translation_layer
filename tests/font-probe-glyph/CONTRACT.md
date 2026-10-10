# FONT-006 — glyph rounding / spacing / bounds flags / synthetic italic contract

Finding: narrow fixtures; the first-font decision was reused for fallback; insufficient coverage
of hinting/linear flags, real versus synthetic italic and variable-font settings.

- API/inputs: `Paint.getTextWidths` (both overloads, including `getTextWidths(String,float[])`
  which was a capability gap), `getTextBounds`, advance widths, `letterSpacing`, `textSkewX`,
  hinting/linear flags, fake-bold and real oblique/bold faces, variable-font settings, and text
  resolving to multiple font runs.
- Controlled configuration: bundled `DejaVuSans.ttf` `ae7b7855…7280`, `DejaVuSans-Oblique.ttf`
  `2d7b524c…73de`, `DejaVuSans-Bold.ttf` `5c1247ac…c895`; flags/sizes per cell; the same frozen
  APK runs on Android and ATL.
- Observation: per-cell JSON with per-glyph advances and bounds, emitted between the JSON
  sentinel markers and ended by the completion marker.
- Tolerance: integer advances/bounds exact; rule authority: `verify_font_glyph.py`.
- RED/GREEN: RED lacked the `getTextWidths` overload entirely; GREEN after `60d01a68`
  (overloads + native advance fill), `4719b5e8` (bounds flags: `FT_LOAD_NO_HINTING`, width-path
  pen, `FT_Outline_Embolden /34`, computeFakery) and `0bdcfe49` (`letterSpacing ls*size` + edge
  half-adjust; skewX forces no-hinting, linear advances and an `FT_Set_Transform` shear) —
  41 cells / 149 advances, 2-run deterministic.
- Frozen APK: `font-006-glyph-probe.apk`
  sha256 `b1cdb6143143838ff693be99c6573bb652dcb637cf75d24cb0b661f5dc1e7d41`.

Commands: `sh build.sh`, `bash run_font_glyph_selftests.sh`, and
`python3 verify_font_glyph.py <android-ref.json> <candidate.json> --allow-bare`.
