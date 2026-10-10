# FONT-004 — getTextBounds contract

Finding: `getTextBounds` coverage was inadequate — width/height alone did not pin
left/top/right/bottom, alignment, empty/whitespace or Unicode/fallback behaviour.

- API/inputs: `Paint.getTextBounds(String, int, int, Rect)` and supported overload/range
  behaviour, with alignment, baseline, empty/whitespace strings, Unicode/combining and
  non-BMP input, and fallback cases.
- Controlled configuration: bundled `assets/DejaVuSans.ttf`
  sha256 `7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954`; the same frozen APK
  runs on Android and ATL with identical font bytes on both sides.
- Observation: per-cell JSON with all four bounds fields (not just dimensions), emitted
  between the JSON sentinel markers and ended by the completion marker.
- Tolerance: every integer field exact. Authority: `verify_font_bounds.py`.
- RED/GREEN: the RED baseline was rejected — ATL's private FreeType defaulted to
  interpreter-version 40 while Android uses 35; the candidate `03725484` (private interp-35
  `FT_Library` inside the paint natives) passes 43 cells / 172 integer fields exactly.
- Decision probe FONT-005 (`tests/font-probe-bounds2/`) tabulated the hinting/pen law that
  selected the fix; its frozen APK sha256 is
  `3813cd17822767727974798a7cec35f52478d77e5ab78185791417eeb3b7fb8e`.

Commands: `sh build.sh`, `bash run_font_bounds_selftests.sh`, and
`python3 verify_font_bounds.py <android-ref.json> <candidate.json> --allow-bare`.
