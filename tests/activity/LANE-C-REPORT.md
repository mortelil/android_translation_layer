# Lane C (activity) - reconciliation report

Lane: C (activity controller, AOSP lifecycle paths, saved state/recreation, GTK
event adaptation).
Worktree `/home/morten/ATL/lifecycle-foundation/activity/repo`, branch
`lifecycle/activity`.
Evidence root `/home/morten/ATL/agent-work/LIFECYCLE-FOUNDATION/activity`
(container stdout / run data) and the committed in-repo evidence
`repo/tests/activity/evidence/**` (app-written run artifacts).
Lane build identity for every run below: `repo/build-mobile/android-translation-layer`
sha256 `21e17b662ee91a09c66ffcd31c4ae029029e552ec1ad80b8493e57086c82ad55`
(149952 bytes, mtime 2026-10-15 06:56); hax.jar from that build (meson registers
ATLActivityController). Frozen refs: ATL main ab7345cf, AOSP 94b4c163b7df.
No lane A/B/D file, no `acceptance.csv`, and no shared runtime output was
touched. All builds/runs went through
`resources.py --team activity --kind <build|atl>`.

## 1. Per-row status

Method = the frozen method text in `acceptance.csv`. "in-repo" = committed
inside `repo/tests/activity/evidence` (the run artifacts themselves);
"ws" = the workspace evidence dir (container stdout, run data, large traces).

| Row | Method (from acceptance.csv) | Status | Evidence |
| --- | --- | --- | --- |
| A01 | controller unit: state transitions table + invariant test (path closure) | COMPLETE | in-repo `evidence/a06/unit-focus-invariants.log`; ws `A01-A02-path-invariants.log` - marker `ACTLPATH ALL_DONE 191 PASS 0 FAIL` |
| A02 | invariant test: ON_RESTART never persists; always resolves to ON_START+ | COMPLETE | same two files as A01 (`restart-not-a-transition-endpoint`, 43 valid pairs + 21 guarded intermittent-endpoint pairs) |
| A03 | code review: single dispatch site; GTK events go through one seam | COMPLETE | in-repo `evidence/a03-single-dispatch.txt` (HEAD 2711907a; ws copy `seam-v1/a03-single-dispatch.txt`), ws `seam-v1/symbols.txt` (C entry points unchanged, 4 retired `Java_android_app_Activity_*` natives gone) |
| A04 | probe: dispatchActivityCreated/onPostCreate ordering; Application callbacks fire once | COMPLETE | ws `a04-strict3/{verify.log,trace.log,results.json,close.log,atl-stdout.log}` + in-repo `evidence/a04/{verify.log,candidate-atl-app.trace,close.log,atl-stdout.log}` - real lifecycle-probe v2.1 run, 495 LIFE_EVT, 0 exceptions, `LIFE_SCEN_BEGIN/END S3D2` (487/538) with the window closed from outside, `System.exit called, status: 0`; `verify_lifecycle.py` exit 0, exactly the 10 pinned DIVERGENCE lines, no BASELINE_MISMATCH. Earlier sets, kept as-is and not overwritten: `a04/` (475 events, external close never attached, superseded - `a04/atl-stdout.log` + `a04/candidate-atl-app.trace` are also committed in-repo) and `a04-strict/` (`a04-strict/results.json`, `a04-strict/trace.log` plus the GTK close diagnostics `a04-strict/{gtk-close-fixed,gtk-close-probe,gtk-close-probe-wm,gtk-env-nowm}/`) |
| A05 | probe: recreate() inside onResume; finish() inside onCreate; exactly one destroy, no double transitions | COMPLETE | **both halves now executed**. finish-half: in-repo `evidence/a05-a09/sdk{27,28,35}/` + ws `a05-a09/` (34 PASS 0 FAIL, 6 `A05-finish-in-create-*` checks). recreate-half: in-repo `evidence/a05-incallback-recreate/README.txt` + `sdk{27,28,35}/{results.json,aprobe-trace.log}` + ws `a05-incallback-recreate/sdk{27,28,35}/atl-stdout.log` - `APROBE ALL_DONE 41 PASS 0 FAIL` at targetSdk 27/28/35, 7 new `A05-incallback-recreate-*` checks (additive; the v1 34 unchanged) |
| A06 | unit: focus change without lifecycle change; state assertions | COMPLETE | in-repo `evidence/a06/unit-focus-invariants.log` (`ACTLFOCUS ALL_DONE 9 PASS 0 FAIL`; ws copy `a06/unit-focus-invariants.log`) + the runtime half in `evidence/a05-a09/sdk*/results.json` (`A06-focus-not-yet-at-resume`, `A06-runtime-focus-after-resume`, `A06-runtime-focus-events`) |
| A07 | probe: A->B->back result delivery; navigation contract unchanged vs baseline | COMPLETE | in-repo `evidence/a05-a09/sdk*/results.json` (`A07-nav-order`, `A07-nav-framing`, `A07-return-frame`, `A07-single-B-instance`, `A07-result-*`, `A07-new-intent-*`, `A07-explicit-component-launch`) |
| A08 | probe: recreate with editable view state + app-supplied saved state; instance identity differs; state restored | COMPLETE | in-repo `evidence/a05-a09/sdk*/results.json` (`A08-recreate-distinct-instance`, `-old-destroyed-once`, `-new-created-once`, `-new-resumed-once`, `-save-state-once`, `-restored-app-state`, `-restored-view-state`) |
| A09 | probe matrix with targetSdk 27/28; ordering difference documented | COMPLETE | in-repo `evidence/a05-a09/sdk{27,28,35}/results.json` (`A09-save-order-targetSdk`: sdk27 saveIdx 89 < stopIdx 92; sdk28/35 stopIdx 89 < saveIdx 92; `A09-repeated-recreate-{distinct,clean,restored,lifecycle}`, `A09-recreate-a-resumed-once`) + the divergence section 2 below |
| A10 | document limitation in final report; no over-claim in tests | COMPLETE | in-repo `evidence/a10-no-process-death-restore.txt` (boundary + the on-disk greps that show what is absent) + `APROBE_INFO A10-limitation` in every probe run (`evidence/a05-a09/sdk*/aprobe-trace.log`, `evidence/a05-incallback-recreate/sdk*/aprobe-trace.log`); statement in section 3 |
| P11 | classloader-probe rerun green on combined HEAD; no loader visibility patch in diff | **PARTIAL (lane-C half COMPLETE; the combined-HEAD run is the integration gate)** | lane candidate: in-repo `evidence/p11/{verifier.log,runner.log,results.json}` + ws `p11/{completion.txt,verifier.log,stdout.log,stderr.log,runner.log,results.json,apk.sha256,atl-binary.sha256,lane-head.txt}` - `ATL-PROBE STATUS=OK exit=0 marker=CLPROBE_JSON_END`, `CLPROBE VERDICT=PASS platform=atl run_id=atlrun-1792042375-347043 checks=41 passed=41 failed=0` with `--expect-loader-source own-classloader` (pinned Firefox baseline 5418fb3), and the clean-diff assertion: the lane diff contains no `art/`, `loader/`, `bionic/` or `environ` file and adds no FindClass/class-visibility workaround. The frozen method's "on combined HEAD" run is lane D/integration's (this lane cannot build or run the combined tree). Next action in section 4 |
| V01 | compare guaranteed ordering only | input supplied, row not lane C | the Android-vs-ATL ordering comparison this lane owns is section 2: the 10 pinned cross-Activity divergences, each with the AOSP anchor and the reason it is pinned. V01 itself is a test/coord row (lane D) |
| V02/V03/V04/V05 | test/coord rows | not lane C | untouched |

Additional green checks owned by this lane, committed with the same runs:
analyzer selftests `ANALYZER SELFTESTS OK: 62/62` (`evidence/analyzer-selftests.log`,
lane HEAD 8fd1c20b), build/seam evidence `seam-v1/{build-seam.log,symbols.txt,unit-path-invariants.log}`.

## 2. Cross-Activity ordering divergences (A04/A09 input for A10 and V01)

`verify_lifecycle.py` reports exactly 10 DIVERGENCE lines and no
BASELINE_MISMATCH (ws `a04-strict3/verify.log`, in-repo `evidence/a04/verify.log`,
byte-identical between the three A04 artifact sets):

```
DIVERGENCE S2, S4.1, S4.2, S4.3, S4.4, S4.5, S3D1, L-SNAP, L-REGD, L-EQUALS:
candidate cross-Activity Activity ordering differs from the Android reference
and is pinned to the ATL baseline
```

What differs (recorded, not hidden). For an A -> B -> A launch the Android
reference (real API-37 run + contract manifest) is
`A.onPause, B.onCreate, B.onStart, B.onResume, B.onPause, A.onResume,
B.onStop, B.onDestroy`: the programmatic cycle is fast, so Android's deferred
stop of A is cancelled and A never leaves RESUMED - it emits no onStop, no
onRestart and no second onStart on return. ATL produces
`A.onPause, B.onCreate, B.onStart, B.onResume, A.onStop, B.onPause, A.onRestart,
A.onStart, A.onResume, B.onStop, B.onDestroy`.

Why it is pinned to the ATL baseline, and why that is AOSP-consistent. ATL has a
single GTK window, so the covered activity is genuinely invisible and gets both
onPause and onStop (the retired C dispatcher did the same; the pinned baseline
models exactly that). The return path then has to walk ON_STOP -> ON_RESUME,
which AOSP resolves through ON_RESTART, ON_START, ON_RESUME
(`TransactionExecutorHelper.getLifecyclePath`), which is why the returning
activity carries an onRestart/onStart group. The launch order the controller
implements is the AOSP order (`ActivityThread.handleLaunchActivity` /
`handleStartActivity`, pinned `ActivityThread.java:4585-4640`: pause -> create ->
restore -> postCreate -> resume, covered activity stopped once the incoming one
is resumed) and a finishing activity is never saved (`callActivityOnStop`,
`ActivityThread.java:6310-6394`). So the divergence is "one window, so A really
stops", not an ordering error inside a transition.

How it is pinned and reproducible: `tests/lifecycle-probe/fixtures/baseline-atl.trace`
(regenerated by `python3 tests/lifecycle-probe/make_fixtures.py`, bit-exact, with
the rationale and the observer-list derivation beside it in
`fixtures/baseline-atl.rationale.md`; commit 06e986d4) and the
`cross_activity_ordering.divergence` prose in the probe manifest. PINNED mode
pins exactly one dimension: the per-scenario Activity-only `.pre`/`.post` framing
sequence (`activity_order`).

What is claimed and what is not. Claimed: (a) per-activity-event semantics - every
callback event's lifecycle state, bundle null-ness and observer set is validated
against the Android-derived manifest, and (b) the per-activity pre/post framing of
each callback. Not claimed: cross-Activity ordering equal to Android for the 10
pinned scenarios, and no ordering claim at all for orderings that are not
guaranteed on either side (`ActivityLifecycleCallbacks` has no restart callback,
so the extra onRestart group is framing-only, no callback tuples). A
non-zero exit with BASELINE_MISMATCH would mean controller and baseline disagree -
none appeared in any run.

A09's targetSdk ordering difference (same "document the ordering difference"
requirement) is the documented saved-state split, executed on real runs:
targetSdk 27 saves before stop (saveIdx 89 < stopIdx 92), targetSdk 28/35 stop
before save (stopIdx 89 < saveIdx 92) - `A09-save-order-targetSdk` in
`evidence/a05-a09/sdk{27,28,35}/results.json`.

## 3. A10 limitation statement (no process-death restoration claim)

The seam implements the IN-PROCESS lifecycle state engine and the in-process
instance-state round trip (save -> destroy old -> create new distinct instance ->
restore -> resume; A08/A09 and the new A05 in-callback recreate evidence). It does
NOT implement, and no test claims, Android's saved-state persistence across
process death:

- no persisted saved-state store: the only writer is
  `Activity.onSaveInstanceState` -> `Bundle` in memory, handed to the
  controller's pending-saved-state slot;
- no `Parcel.marshall`/`writeBundle` of that bundle to disk anywhere in the
  activity path;
- no process-death restart path: the host never re-creates an activity tree in a
  fresh process (launcher/AMS task-restore responsibility, architecture section 8
  boundary);
- after the ATL process exits, the app data dir holds only what the app itself
  persisted, never framework state.

Test-side non-over-claim: every probe run emits
`APROBE_INFO A10-limitation "no process-death restore ... recreation state is
in-process only"` and no probe scenario asserts a cross-process restore.
Full text and the on-disk greps: `evidence/a10-no-process-death-restore.txt`.

## 4. Rows that are not COMPLETE, with the exact next action

1. P11 - the lane half is green, the row's frozen method is not satisfied yet.
   The row reads "classloader-probe rerun green on **combined HEAD**; no loader
   visibility patch in diff". Exact next action (integration owner, lane D, in
   the combined worktree - this lane must not build or run the combined tree):
   ```
   ATL_LANE_DIR=<combined worktree root> \
   ATL_RESOURCES=/home/morten/.omp/profiles/atl-lifecycle/agent/resources.py \
   sh <combined repo>/tests/activity/run-classloader-probe.sh <out-dir>
   ```
   then assert `CLPROBE VERDICT=PASS platform=atl checks=41 passed=41 failed=0
   --expect-loader-source own-classloader` and re-check the combined diff for
   `art/`, `loader/`, `bionic/`, `environ` files and for added FindClass /
   class-visibility workarounds. The lane-candidate result to compare against is
   `evidence/p11/verifier.log`.
2. Every other lane-C row (A01-A10) is COMPLETE at lane HEAD plus this session's
   additive A05 commit. All of them are lane-local evidence: A01-A10 do not need
   a combined-HEAD rerun for their own gate, but the combined tree must be at
   least smoke-checked by integration (optional combined rerun of
   `run-probe.sh <apk> <out> APROBE_MARK 240`, which now reports 41 PASS 0 FAIL
   per targetSdk 27/28/35, and one `ATL_PROBE_EXTERNAL_CLOSE=yes` A04 rerun).

## 5. Lane-C scope and limitations (documented, not over-claimed)

- Single platform: x86_64 (see row V04). No ARM/Nura result is claimed.
- The activity probe is one APK launched by the ordinary launcher with
  explicit-component launches; there is no Instrumentation-driven, manifest-ordered
  or process-death scenario, and no multi-window / focus-loss scenario beyond the
  recreate window sampled in A06.
- The A05 in-callback evidence covers one in-callback recreate (ActQ instance #1 ->
  #2) plus the already-covered repeated recreate and finish-inside-onCreate paths;
  it does not enumerate every callback as a recreate trigger.
- A04's externally-driven scenario is the GTK close-request path with a window
  manager present (`ATL_PROBE_WM`); without a WM the close semantics differ
  (documented in `evidence/a04/README.txt`).
- Nothing in this lane claims Android-equal cross-Activity ordering (section 2) or
  process-death restoration (section 3).

## 6. What this session changed

1. Reconciled every row against the evidence on disk (frozen method text vs the
   artifact). All 11 lane rows had evidence; the single genuine execution gap was
   A05's "recreate() inside onResume" half, because the probe driver deferred every
   action with `postDelayed` and therefore never exercised the in-callback
   reentrancy path.
2. Executed that missing case additively: new `ActQ` activity that calls
   `recreate()` inside its own `onResume`, 7 new `A05-incallback-recreate-*`
   checks, run at targetSdk 27/28/35 -> `41 PASS 0 FAIL` each; the pre-existing 34
   checks and the whole v1 49-entry result list are unchanged (the v2 runs contain
   every v1 entry with the same result plus 10 new entries). New evidence:
   `evidence/a05-incallback-recreate/` (in-repo) and
   `ws a05-incallback-recreate/sdk{27,28,35}/` (container stdout);
   `evidence/a05-a09/` was NOT overwritten. Probe revision
   `atl-activity-seam-probe-v2`; test code only, no runtime file touched.
3. Documented the A04/A09 cross-Activity divergences (section 2, feeding A10 and
   V01) and the A10 limitation (section 3).
4. Corrected the stale in-repo `tests/activity/DESIGN-CHECKPOINT.md` header
   ("surveyed, not yet implemented") and recorded session 9 there.

Row status after this session: A01 COMPLETE, A02 COMPLETE, A03 COMPLETE,
A04 COMPLETE, A05 COMPLETE (both halves executed), A06 COMPLETE, A07 COMPLETE,
A08 COMPLETE, A09 COMPLETE, A10 COMPLETE (documented limitation, no over-claim),
P11 PARTIAL (lane half COMPLETE; combined-HEAD run owned by integration).