# OMP integration review, 2026-10-11

Base: GitHub main `aade251e8d91584b2c05422fe3de1488cf9e352d`.
Sources: review-repairs `6df114feb09a9c9b5eabc7c609ffebc8bada4e6c` and
local-services `253381c3fd093d7c3b6dab92b2fc60775bc196a3`.
The original dirty checkouts were backed up and left unchanged.

## Scope and grouping

A: repaired lifecycle analyzer/dispatch, Fragment tag contracts, font metrics,
bounds, advances, variation/fallback and their existing test controls.
B: integration defects in Fragment commit timing/reuse, service dispatch/lifetime,
shaped mark positions and Font.Builder style inference. These were repaired before
final testing, so A+B is delivered in one PR with the original logical commits.
The overlapping lifecycle/Fragment prerequisites were taken only once; nine
service-only commits were imported from local-services. No generated Manifest/R
or fixture absolute-path churn was committed.

## Additional repairs

- Restore Application's complete Apache-2.0 notice; provenance is pinned to AOSP
  frameworks-base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` in the commit message.
- Queue Fragment commit, return -1 without back stack, reject a second commit.
- Preserve HarfBuzz mark offsets only for a matching glyph sequence. Infer
  Font.Builder style using the existing file-font backend.
- Preserve bound services on stopSelf; cache per-intent binders; scope dispatchers
  to context/connection/component; handle binder replacement and connection reuse;
  remove all components when unbinding. Explicitly reject remote/isolated services.
- Fix acceptance regression environment ordering so sourcing test-env cannot
  replace the selected candidate with the old shared build. Build only the
  configured candidate, without reinstalling shared dependencies.

## Executed verification

All ATL builds/runs used Alpine Toolbox atl_dev; Android references used the
existing host emulator. Results were newly written to the private integration
workspace; old reference evidence was not changed. The original probe runner was
reused with private data/cache directories and process-map capture. Its host PID
match needed regex escaping for APK filenames containing parentheses. Short cold
UI runs produced blank captures; longer runs completed and were inspected visually.

| Test | Result |
|---|---|
| Analyzer positive/negative controls | lifecycle 62/62; font 17/17, bounds 20/20, glyph 19/19, fallback 22/22; services 29/29 |
| Frozen font contracts | metrics 12 cells/120 fields; bounds 43/172 exact integers; glyph 41/149 advances; fallback 52 cells, bitmap caveat below |
| Extended font APK, identical Android/ATL hash | 61 cells/217 advances, exact bounds; inferred bold weight and explicit overrides |
| Lifecycle v2.1 | 428 events; per-activity validation passes with exactly 10 pre-existing pinned cross-activity divergences |
| Frozen service v5 APK | 15 supported scenarios/372 ordered events match Android |
| New SVC-018 APK | 36 ordered events match Android, covering the repaired Fragment/service cases |
| Unsupported remote process | explicitly rejected; the old build is rejected by the separate unsupported-behavior check |
| Negative implementation controls | old runtime rejected by SVC-018; stacked marks bottom=3 vs Android 6; old Font.Builder fails inferred weight |
| mobile-smoke | ATL TEST APK PASSED on selected candidate |
| Immich x86 UI | server text field accepted text; Settings opened; normal close |
| Messenger x86 UI | both login fields accepted dummy text; password visibility toggled; normal close; no login |
| LocalSend x86 UI | Receive, Send, Settings and return navigation; normal close |

The final acceptance stages were executed separately. No aggregate
READY_FOR_REVIEW verdict is claimed in place of their individual evidence.
Artifact/source identity was checked with a newly recorded manifest; process maps
identify the candidate .so and api-impl dex-cache origin. SHA-256 inventories cover
candidate binaries/JAR/resources, core JARs, APKs and loaded ART/androidfw/WolfSSL.
Generated Manifest/R inputs are separately hashed because the build regenerates
them; they are not an authored source change.

Local raw evidence: `~/src/atl-omp-integration-20261011/`, notably `verified/`,
`verified-contract.log`, `verified-regression.log`, `verified-artifacts.json`,
`service-verified/`, `service-extended-{android,final,negative}/`,
`font-extended-{android2,verified,negative}/` and `ui-verified-*`.
Use tests/acceptance.sh with ACCEPT_ATL_PROBE_RUNNER pointing at the adapted
run-atl-probe.sh there. Rebuild/sign probes with the existing per-probe build.sh;
use FONT_GLYPH_EXTENDED=true and verify_font_glyph.py --extended for the extension.

## Limits, not passes

- LIFE-008 dispatch is callable but lacks a real platform save-state trigger;
  no end-to-end restoration or complete Fragment back-stack support is claimed.
- Cross-activity ordering still has the previously pinned divergence. No new
  order difference was added to the allowed baseline.
- FONT-007 bitmap B is NOT_COMPARABLE with Android's substituted font. Its
  structural checks do not prove Android bitmap parity. Shaping is still bounded
  by the matching Pango/HarfBuzz run; this is not complete Minikin equivalence.
- Remote/isolated service execution, process death/restart and foreground policy
  are unsupported. SVC-014 is intentionally excluded from Android parity and
  checked separately for explicit failure instead of false in-process success.
- A fresh Android run reordered two independent SVC-006 clients compared with the
  frozen reference. The oracle was not weakened; unchanged cases were tested with
  their frozen APK/reference, new SVC-018 with its identical fresh APK/reference.
- App checks are x86 UI regressions, not full functionality: no account login,
  family-photo access, upload, file transfer or Nura verification in this round.

The OMP author history is retained. Review repairs and test execution were assisted
by Codex; passing these bounded contracts does not imply complete Android support.
