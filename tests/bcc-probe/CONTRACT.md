# B-CALLBACKS contract (ComponentCallbacks / ComponentCallbacks2 + dispatch)

Source contract: AOSP `android-17.0.0_r1` (commit `94b4c163`, provenance
`/home/morten/ATL/reference/aosp/`): `android/content/ComponentCallbacks.java`,
`android/content/ComponentCallbacks2.java`, `android/app/Application.java`,
`android/content/ComponentCallbacksController.java`, `android/content/Context.java`,
`android/content/ContextWrapper.java`, `android/app/Activity.java`.

## Frozen probe APK
`org.atl.tests.bcc/.ProbeActivity`, targetSdk 24 / minSdk 24, debuggable,
`android:configChanges="…|uiMode|orientation|…"` (keeps the Activity instance
alive so a platform configuration change reaches `onConfigurationChanged` instead
of recreating the probe). Signed with the shared ATL debug key
(`/home/morten/ATL/font-tests/debug.keystore`, alias `fontprobe`).

Frozen sha256:
`072258ccd45f7d61bbac8651e91aa4997dbc910402c57db415874886a7b8d3d6`

## Protocol
Phase 1 (onCreate, deterministic): type contract, 7 `TRIM_MEMORY_*` constants via
reflection, registration surface, controlled direct dispatch S1. Writes a PENDING
`files/results.json`, prints `BCC_READY`.
Phase 2 (platform): waits up to `wait_ms` (default 45000) for one real
configuration change while listeners L4/L5 stay registered and
`Activity.onConfigurationChanged` is recorded. Finalizes with state
DISPATCH_OBSERVED or NOT_TRIGGERED, prints one sentinel JSON block
(`BCC_JSON_BEGIN`/`BCC_JSON_END`), persists the same JSON to `files/results.json`
and prints `BCC_DONE`. Completion is the marker / file, never process exit.

## Observations emitted (JSON)
`type_contract` (instanceof/isAssignableFrom + interface method presence),
`constants` (7 values), `registration` (Context + ContextWrapper method presence,
multiple-register no-throw, unregister effect), `direct_dispatch` (scenario S1,
ordered trace, callback threads), `platform_trigger` (state + ordered trace),
plus task/run/thread/status/exceptions/config/apk sha256.

## Direct dispatch scenario S1 (frozen, exact)
Register L1,L2,L3 on `getApplicationContext()`; then on the Application
`onTrimMemory(40)`, `onTrimMemory(10)`, `onLowMemory()`,
`onConfigurationChanged(orientation=2, uiMode=0x20)` (each must fan out to
L1,L2,L3 in registration order); unregister L2; `onTrimMemory(60)` (L1,L3 only);
unregister L1,L3; `onTrimMemory(80)` (empty, step still recorded). Callbacks must
run on the calling main thread. The full 23-event trace is pinned identically in
the probe and the comparator.

## Comparator (`verify_bcc_contract.py`, fail-closed)
Both documents non-empty, sentinel-wrapped (or `--allow-bare`), one JSON object,
task `B-CALLBACKS`, distinct non-empty `run_id` per side, `thread=="main"`,
`status=="OK"`, empty `exceptions`, `config.trigger=="uimode_night"`, equal
`apk.sha256` on both sides. Type contract, 7 constants and registration are
compared **exactly** against the AOSP-derived spec AND across sides (so two
identically-wrong sides cannot pass). Direct dispatch must be DISPATCH_OBSERVED
with the exact S1 trace + `["main"]` threads. Platform trigger must be
DISPATCH_OBSERVED or NOT_TRIGGERED (PENDING is rejected); NOT_TRIGGERED requires
an empty trace; if both sides observe dispatch their traces must agree. The
trigger state may differ per side and is reported as dispatch coverage, never as
a pass.
Exit codes: 0 accept, 1 reject (`REJECT <CODE> <reason>`), 2 usage/internal.

## Commands
* Build (container): `sh build.sh <outdir>` → unsigned aligned APK.
* Sign (host): `sh sign.sh <aligned.apk> <out.apk>`.
* Self-test: `bash run_bcc_selftests.sh` (33/33).
* Compare: `python3 verify_bcc_contract.py <android.json> <atl.json> [--allow-bare]`.