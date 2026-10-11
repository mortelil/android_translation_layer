# FONT-003 — font top/bottom metrics contract

Finding: candidate integer conversion rounded normally instead of the reference's
`floor(top)` / `ceil(bottom)`; `FontMetricsInt` coverage was missing.

- API/inputs: `Paint.getFontMetrics()`, `getFontMetricsInt()`, `Paint.FontMetrics` /
  `FontMetricsInt` (`top`, `ascent`, `descent`, `bottom`, `leading`) over fractional text sizes
  with a bundled font.
- Controlled configuration: bundled `assets/DejaVuSans.ttf`
  sha256 `7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954`; explicit text sizes
  and flags per cell; the same frozen APK runs on Android and ATL.
- Observation: one JSON cell per (size, flags, text) with the float and integer metric fields,
  emitted between the JSON sentinel markers and ended by the completion marker.
- Tolerance: integer fields must match exactly (including sign and outward rounding); float
  fields are compared within the comparator's band. Authority: `verify_font_metrics.py`.
- RED/GREEN: pre-fix output (normal rounding) is rejected; ATL candidate `45944d28` passes
  12 cells / 120 fields exactly.
- Frozen APK: `font-003-probe.apk`
  sha256 `973e2fc95e7f450b202d83e7ad548e0e54aacf04a285b57c786a1529d7bcd2cc`.

Commands: `sh build.sh` (inside the atl_dev Toolbox), `bash run_font_selftests.sh`, and
`python3 verify_font_metrics.py <android-ref.json> <candidate.json> --allow-bare`
(exit 0 accept / 1 reject / 2 internal).
