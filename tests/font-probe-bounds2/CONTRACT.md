# FONT-005 — bounds decision probe (reference experiment)

This probe is a discriminating reference experiment, not a behavioural patch: it tabulates the
hinted integer pen/bounds law so the FONT-004 fix could be selected from measurement instead of
an inferred algorithm.

- API/inputs: `Paint.getTextBounds` / advance measurement over a parameter grid that
  distinguishes hinted versus unhinted integer pens.
- Controlled configuration: bundled `assets/DejaVuSans.ttf`
  sha256 `7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954`; the same frozen APK
  runs on Android and ATL.
- Observation: per-cell law candidates tabulated from Android output; law variants b and c were
  rejected, the hinted-integer-pen law was retained.
- Outcome: the retained law justified the private interp-35 `FT_Library` used by the FONT-004 fix
  (`03725484`), recorded in `agent-work/REVIEW-REPAIRS/font-bounds-decision/` and the
  FONT-004 contract.
- Frozen APK: `font-005-bounds-decision.apk`
  sha256 `3813cd17822767727974798a7cec35f52478d77e5ab78185791417eeb3b7fb8e`.

Commands: `sh build.sh` (inside the atl_dev Toolbox). There is no comparator here; the probe
report is the artifact.
