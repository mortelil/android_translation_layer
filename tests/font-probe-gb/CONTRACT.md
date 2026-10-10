# FONT-007 — variable axes, fallback runs, bitmap faces, kerning contract

Finding: fallback resolution reused the first font selected for the whole string; variable-font
settings, multi-run text and bitmap faces were uncovered.

- API/inputs: `Paint.setFontVariationSettings`, `android.graphics.fonts.Font` /
  `FontFamily` / `FontFamily$Builder`, `Typeface.CustomFallbackBuilder`, text whose resolved runs
  use different faces, a raw bitmap face, and GPOS pair kerning.
- Controlled configuration: bundled `DejaVuSans.ttf` `ae7b7855…7280`,
  `Ubuntu-Variable.ttf` `28c4c189…b473`, `NotoSansCJK-Regular.ttc` `b76b0433…690a`,
  `8x13.pcf` `325f347d…2348`; the same frozen APK runs on Android and ATL.
- Observation: per-cell JSON, sentinel markers `FONT007_JSON_BEGIN` / `_END` and completion
  marker `FONT007_DONE`.
- Groups and tolerance: V_paint and V_builder are value comparisons (24/24 each); F (fallback)
  is a capability/structure comparison (2/2); group B (bitmap face) is judged on candidate-only
  structural invariants and is labelled `NOT_COMPARABLE` versus Android. Authority:
  `verify_font_gb.py` + `agent-work/REVIEW-REPAIRS/font-gb/comparator-b-rule.md`.
- Group B contract correction (rule only; frozen APK and both JSONs byte-unchanged): Android's
  `8x13.pcf` cells are not a scaled strike — minikin reads coverage via `HbBlob(base,"cmap")`,
  which is null for a raw non-sfnt PCF, so Android falls back to a scalable font, while ATL
  previously returned a `(0,0,0,0)` ink box. Per-glyph scaling ratios are also inadmissible for
  small hinted advances: Android's own frozen FONT-006 reference gives `l` advance 3→9 across a
  size ratio of 2.0491. The rule therefore tests aggregates (sum of advances, ink width/height
  within `2.0491 ±25%`) plus per-glyph monotonic non-decrease, and still rejects the pre-fix
  uniform `[8,8,8,8,8]` / empty-ink output.
- RED/GREEN: GREEN after `928097ec` (variation settings), `c48b2533` + `bb521e26` (`fonts.Font`
  family + `CustomFallbackBuilder`, per-run FT face resolution from each run's `FC_FILE`/`FC_INDEX`),
  `e639dc72` (HarfBuzz GPOS pair kerning in widths and bounds; `FT_Get_Kerning` fallback) and
  `ad7f6a7c` (coverage-skip: a face with no readable cmap is empty coverage, so the run resolves
  to the fallback family). 52 cells / 134 advances, 2-run deterministic, selftests 22/22.
- Frozen APK: `font-gb-probe.apk`
  sha256 `036ac1dd312c2ffce6a943764f98518db3d31f9a03db34bffc170827acd008ad`.

Commands: `sh build.sh`, `bash run_font_gb_selftests.sh`, and
`python3 verify_font_gb.py <android-ref.json> <candidate.json> --allow-bare`.
