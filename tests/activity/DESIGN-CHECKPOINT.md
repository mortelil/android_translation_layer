# Lane C design checkpoint — JNI seam + host events (pre-implementation)

Status: surveyed, not yet implemented. Written at the 2026-10-15 budget cutoff
so the next resume does not redo the survey. Companion to DESIGN notes in
ATLActivityController.java's header. All facts below were verified on disk this
run (repo @ d0c8fefb).

## Verified current native contract (files D owns — signatures FROZEN)

src/main-executable/main.c and src/main-executable/back_button.c consume from
src/api-impl-jni/app/android_app_Activity.c (header
src/api-impl-jni/app/android_app_Activity.h):

- `void activity_start(JNIEnv*, jobject)` — main.c:325, :417(drop URI), :843
  (main launch) and via Java_android_app_Activity_nativeStartActivity.
- `void activity_window_ready(void)` — main.c:206 (polled GTK width hack).
- `void activity_close_all(void)` — main.c:53 (GTK "close-request" → app_exit).
- `void current_activity_back_pressed(void)` — back_button.c:11.
- `handle_cache.activity.class` — main.c:412,656 (createMainActivity lookup).

=> The seam refactor keeps every one of these C symbols and signatures; they
delegate to ONE Java entry: `ATLActivityController.hostEvent(int code, ...)`
(HOST_EVENT_* codes already defined in the controller: START=1, BACK=2,
WINDOW_CLOSE_REQUEST=3, WINDOW_READY=4, NEW_INTENT=5).

## Verified current C-side behavior to port (android_app_Activity.c @ d0c8fefb)

- backlog: GList, index 0 = top; activity_current jobject global ref.
- activity_unfocus: onPause (only if !paused field), onStop,
  onWindowFocusChanged(false). activity_focus: performStart, stack visible
  child = decor, performResume, onPostResume, onWindowFocusChanged(true),
  each step aborts if finishing became true. Back-sensitivity:
  backlog>1 || concrete class resolves a different onBackPressed MethodID
  than base Activity's => back_button_set_sensitive(true).
- activity_start: unfocus current, performCreate(NULL), early-finish check
  (finishing right after performCreate => never added to backlog), onPostCreate,
  backlog prepend, decor add to GtkStack, update_current.
- nativeFinish: remove self from backlog, update_current (unfocus old current,
  focus next), activity_close(self) [stack remove + onDestroy], then if
  backlog empty && window: g_idle_add delayed gtk_window_close.
- nativeResumeActivity(clazz, intent): find topmost backlog entry of class;
  pop everything above it (close them), singleTop resume_top case: if it was
  the current non-paused activity: onPause, onNewIntent, performResume,
  onPostResume; else just onNewIntent. Returns found.
- activity_close_all: NULL backlog, update_current, then close+unref all.
- activity_window_ready: onWindowFocusChanged(true) for EVERY backlog entry
  (imprecise; new design: current only — invisible to lifecycle-probe which
  does not observe focus).
- isTaskRoot: == g_list_last(backlog) (bottom entry).
- handle_cache activity IDs used: performCreate, onPostCreate, performStart,
  performResume, onPostResume, onWindowFocusChanged, onDestroy, onStop,
  onPause, onBackPressed (override detection), onNewIntent. After the seam
  only `.class` (main.c) stays; all dispatch moves into the controller.
- Natives that STAY native (GTK-only): decor add/remove/show via JNI field
  walk window.decorView.widget; close-if-last (delayed gtk_window_close);
  back_button_set_sensitive; isInMultiWindowMode (gtk maximized);
  nativeOpenURI/nativeFileChooser (libportal/GTK, unrelated to lifecycle).

## Verified probe contract facts (tests/lifecycle-probe v2.1)

- Reference S2 shape (Android API-37 real + contract manifest): A.onPause →
  B.onCreate → B.onStart → B.onResume → B.onPause → A.onResume → B.onStop →
  B.onDestroy. The returning A emits NO onRestart/onStart on Android because
  A was never stopped (fast programmatic cycle, deferred stop canceled).
- ATL model: single GTK window => covered activity is genuinely invisible =>
  A gets onPause AND onStop when B comes up (existing, pinned divergence in
  manifest.json cross_activity_ordering + baseline-atl.trace).
  Return path must therefore emit A.onRestart → A.onStart before A.onResume
  (getLifecyclePath(ON_STOP→ON_RESUME) = [RESTART, START, RESUME]).
  OLD ATL never called onRestart (verified: baseline-atl.trace contains none).
- Baseline regeneration (coordinator-APPROVED divergence): extend
  make_fixtures.py atl_transform to ALSO insert an ActA onRestart framing
  group (pre/post only — callback_map has NO observer callback for
  onRestart) immediately before the already-inserted ActA onStart group on
  return; record rationale beside the artifact.
- Launch ordering target for the controller (AOSP + GTK model):
  A.performPause → B.performCreate → (restore if relaunch) → B.performStart →
  B.onPostCreate → B.performResume (onPostResume inside performResume,
  landed d0c8fefb) → A.performStop → focus-changed flips. This matches the
  pinned baseline (A.onStop before B.onCreate? NO — see caveat below).
  CAVEAT: the current atl_transform inserts A.onStop BEFORE B.onCreate (old
  ATL unfocused first). Decide at implementation: keep baseline shape
  (pause+stop A before B.create — minimal baseline churn) or AOSP shape
  (A.pause before B.create, A.stop after B.resume — then baseline transform
  moves the A.onStop insertion point too). Either way regenerate the baseline
  and document. The probe validates per-event LC/FT values against the
  Android reference regardless of ordering pin.
- finish() from resumed (S2/S3D1 return): B.performPause → A
  moveToState(ON_RESUME) (emits A.onRestart/onStart now) → B.performStop →
  B.performDestroy → decor remove → close-if-last.
- finish() inside onCreate: after performCreate, if finishing → performDestroy
  only, never enter backlog (old C behavior; Android mirrors this via
  ActivityThread isFinishing check, not via path()).
- recreate() = posted single transaction: performPause → save (pre-P before
  stop, P+ after stop, isPreP landed) → performStop → performDestroy with
  finishing=FALSE + changingConfigurations=true → NEW instance via
  internalCreateActivity → performCreate(savedState) → performStart →
  performRestoreInstanceState → onPostCreate(savedState) → performResume.
  Generation bump; relaunching flag suppresses re-entry (A05/A08).
- Activity.paused field: grep pending (bg job) — confirm who sets it before
  removing; the controller's record.state replaces its readers.
- Application.ActivityLifecycleCallbacks dispatch lives inside on* methods
  (probe contract: callbacks fire inside super.*() between pre/post framing);
  Activity.registerActivityLifecycleCallbacks is a no-op — probe registers on
  Application. Verify Application.java dispatch exists (grep pending) before
  A04 probe work.

## Implementation order (next actions)

1. Apply the meson registration patch submitted to integration/inbox
   (0002-register-ATLActivityController-in-api-impl-meson.patch) — either D
   applies it or lane C applies after D acks; production hax.jar needs it.
2. Extend ATLActivityController: records/backlog ops, hostEvent(code,...),
   startActivity/finish/recreate/resume(Class,Intent)/isTaskRoot helpers,
   natives nativeDecorAdd/Remove/Show(Activity), nativeCloseIfLast(remaining,
   native_window), nativeSetBackSensitive(boolean); hasWindowFocus record
   tracking (A06).
3. Rewrite android_app_Activity.c: keep C symbols + JNI natives that must
   stay native; all lifecycle dispatch becomes hostEvent calls into the
   controller. Prune handle_cache activity IDs (keep .class only).
4. Activity.java: nativeStartActivity/nativeResumeActivity become plain Java
   statics delegating to the controller; finish()/recreate() post controller
   transactions; hasWindowFocus() reads the record; nativeFinish removed.
   Instrumentation.java:177 and ContextImpl.java:476,485 need no signature
   change (statics stay static).
5. Regenerate baseline-atl.trace per above; build; run lifecycle-probe v2.1;
   write tests/activity probes for A05/A07/A08/A09 (targetSdk 27/28 variants
   via separate probe APKs); P11 classloader-probe rerun + diff assertion.

## Confirmed after cutoff (bg grep results, repo @ d0c8fefb)

- Application.java HAS the full registry + dispatch (register/
  unregister/collectActivityLifecycleCallbacks + dispatchActivityCreated/
  Started/Resumed/Paused/Stopped/Destroyed/SaveInstanceState, lines
  51-192+); Activity.java dispatches inside onCreate/onStart/onResume/
  onPause/onStop/onDestroy/onSaveInstanceState via getApplication().
  dispatch* — the probe's "inside super.*()" contract is already met.
- Activity.paused: written only by onResume (false, line 310) and onPause
  (true, line 330); the only reader is the C seam
  (_GET_BOOL_FIELD(activity, "paused")). When the C readers are retired the
  controller's record.state replaces it and the field can be dropped.

## Session 3 (LaneCResume2) — seam implementation design frozen and verified

Committed: 7d45f45b "meson: register ATLActivityController in api-impl srcs"
(self-applied per coordinator ruling, identical to
integration/inbox/0002-...patch; D applies the same line at integration).
Verified after full mobile build (JOBS=2, exit 0):
`unzip -l build-mobile/src/api-impl/hax.jar | grep ATLActivityController`
shows ATLActivityController.class + $ATLActivityRecord.class. Worktree clean.

### AOSP anchors re-verified against the pinned source this session

- Launch order is AOSP-shaped: performCreate → performStart →
  performRestoreInstanceState (only when saved state exists) → onPostCreate →
  performResume — ActivityThread.handleStartActivity (pinned
  ActivityThread.java:4590-4635; restore call sites 4613/4617, postCreate
  4626/4629). Old ATL ran onPostCreate BEFORE performStart; the controller
  switches to the AOSP position (probe-invisible: onPostCreate is not framed
  by ActA/ActB and has no observer callback).
- Activity.onSaveInstanceState: the view-hierarchy save is the FIRST
  statement inside onSaveInstanceState (pinned Activity.java:2613-2620,
  outState.putBundle(WINDOW_HIERARCHY_TAG, mWindow.saveHierarchyState())),
  not in performSaveInstanceState. ATL's Window.saveHierarchyState/
  restoreHierarchyState already exist; TextView.SavedState (text round-trip)
  already exists (TextView.java:35-56). A08 editable-view restoration needs
  the first-statement addition in Activity.onSaveInstanceState + a view ID.
- Activity.performNewIntent EXISTS AOSP-verbatim (Activity.java:9355-9361:
  onNewIntent(intent); no setIntent inside; ActivityThread.java:6939 comment
  shows the framework preserves the record intent — the controller must NOT
  update record.intent on new-intent delivery).
- Destroy is NEVER a lifecycle-path walk: AOSP executes DestroyActivityItem
  directly (handleDestroyActivity → performDestroy) — getLifecyclePath is
  only used for [CREATED..RESUMED] moves. Controller rule: ON_DESTROY is
  always a direct performState(r, ON_DESTROY) call; moveToState must never
  target ON_DESTROY (getLifecyclePath(ON_CREATE, ON_DESTROY) would walk UP
  through ON_START/ON_RESUME — the finish-in-onCreate skip mirrors
  ActivityThread instead: destroy directly, never start).
- atl_jni_handle_uncaught_exception NEVER RETURNS (report + exit(1),
  jni_exceptions.h contract): use only at GTK/GLib boundaries with no Java
  caller (activity_window_ready/close_all/back entrypoints). activity_start
  must keep the pending-return contract: main.c:325/417/843 all call
  atl_jni_handle_uncaught_exception(env, "Activity startup") after it.
- Launcher main.c calls createMainActivity via handle_cache.activity.class
  (main.c:412,656); fileChooserResultCallback lookup uses .class
  (android_app_Activity.c:340). After the seam rewrite handle_cache.activity
  keeps ONLY .class; all 10 MethodIDs are deleted (no user remains once the
  controller does back-override detection via Java reflection
  getClass().getMethod("onBackPressed").getDeclaringClass() != Activity.class
  — equivalent to the old C MethodID comparison).

### Probe/baseline facts established this session (drives the baseline regen)

- EVERY A→B→A scenario drives a real launch (S2, S4.1-5, S3D1, L-SNAP,
  L-REGD, L-EQUALS + terminal L-THROW: ProbeApp.handleBResumed/scheduleStartB
  cycle), but make_fixtures.py atl_transform only rewrites S2
  ("if sid != S2: return out"). A real ATL run would emit the divergence
  groups in all of them → BASELINE_MISMATCH on S4.1 in PINNED mode
  (compare_baseline pins activity_order for every non-terminal, non-external
  scenario; L-THROW is terminal/validate_terminal, S3D2 externally_driven).
  The probe has never been green against a real ATL run; baseline
  regeneration MUST generalize the transform to every scenario containing an
  ActB onCreate: insert ActA onStop group right before the scenario's ActB
  onPause event, and ActA onRestart (framing only, no callbacks —
  callback_map has no onRestart) + ActA onStart groups immediately before
  ActA onResume. Observer callbacks for the inserted groups must derive from
  each scenario's own observer set (the current transform hardcodes
  AppObs-A/AppObs-B — wrong for S4.2+). Extra groups are accepted by
  compare_groups/_validate_extra_group as long as well formed (pre/post
  framing, bundle default "-" when callback_map lacks the event);
  terminal L-THROW prefix validation also tolerates them (compare_groups),
  so no manifest change is needed.
- New-controller framing per launch scenario (AOSP shape + pinned
  divergence): A.onPause, B.onCreate, B.onStart, B.onResume, A.onStop |
  B.onPause, A.onRestart, A.onStart, A.onResume, B.onStop, B.onDestroy.
- ATL run mechanics: scripts/mobile/run.sh (exec build-mobile/
  android-translation-layer <apk>) via toolbox + dbus-run-session +
  xvfb-run (tooling.md §3); probe writes trace.log + results.json to
  getFilesDir() under ANDROID_APP_DATA_DIR (set it to a lane-private dir);
  poll stdout for LIFE_ALL_DONE + TERMINAL_THROW_DONE, then close the window
  externally for S3D2 and collect trace.log. FRAMEWORK_RES for build.sh must
  point at the lane build-mobile res/framework-res apk.

## Session 4 (lane C) — seam implemented

Status: implemented (Java seam + native seam commits), not yet verified: no
build/test was run in this session (the lead runs the single rebuild).

### The one seam

- C → Java: `android_app_Activity.c` calls
  `ATLActivityController.hostEvent(int code, Object arg)` with
  `HOST_EVENT_START=1`, `HOST_EVENT_BACK=2`,
  `HOST_EVENT_WINDOW_CLOSE_REQUEST=3`, `HOST_EVENT_WINDOW_READY=4`
  (macros mirrored in the C file). `HOST_EVENT_NEW_INTENT=5` stays declared
  but has no native caller: Java callers (ContextImpl:476/485,
  Instrumentation:177) reach `resumeActivity` directly.
- C symbols consumed by src/main-executable keep their exact signatures and
  are now one host event each: `activity_start` (returns with the exception
  pending — main.c:325/417/843 still call
  atl_jni_handle_uncaught_exception afterwards), `activity_window_ready`
  (main.c:206), `activity_close_all` (main.c:53; does NOT close the GTK
  window — app_exit does), `current_activity_back_pressed`
  (back_button.c:11). Those three GTK-boundary entry points call
  atl_jni_handle_uncaught_exception themselves (it never returns).
- `handle_cache.atl_activity_controller.class` is the only cached handle
  needed for the seam; `handle_cache.activity` is pruned to `jclass class;`
  (main.c:412/656 still use createMainActivity, fileChooserResultCallback
  uses the class).

### Controller method surface (android.atl.ATLActivityController)

`hostEvent(int,Object)`; host operations `startActivity`, `finish`,
`recreate`, `resumeActivity(Class,Intent)`, `closeAll`, `windowReady`,
`backPressed`, `isTaskRoot`, `hasWindowFocus`; helpers `current`,
`acquireWindowFocus`, `clearWindowFocus`, `updateBackSensitivity`,
`overridesOnBackPressed`, `targetSdk`, `nativeWindowOf`; record gains
`finishPosted` (posted-teardown exactly-once guard) alongside `relaunching`,
`finishing`, `hasWindowFocus`, `savedState`, `generation`.

The 5 GTK natives (implemented in android_app_Activity.c, JNI header
committed like every other generated header):
`nativeDecorAdd` = gtk_stack_add_child, `nativeDecorRemove` =
gtk_stack_remove, `nativeDecorShow` = gtk_stack_set_visible_child,
`nativeCloseIfLast(remaining, native_window)` = g_idle_add(
delayed_close_window), `nativeSetBackSensitive` = back_button_set_sensitive.
Each returns immediately while an exception is pending.

### Deleted on the C side

File statics `activity_backlog` / `activity_current`, the C lifecycle
dispatcher (`activity_close`, `activity_unfocus`, `activity_focus`,
`activity_update_current`), and the JNI natives `nativeFinish`,
`nativeStartActivity`, `nativeResumeActivity`, `isTaskRoot`.
Deleted in Java: `Activity.paused` (its only readers were the retired C
seam), `Activity.nativeFinish`, and `Activity`'s C-bound
`nativeStartActivity`/`nativeResumeActivity`/`isTaskRoot`/`hasWindowFocus`/
`finish`/`recreate` bodies (all now delegate to the controller; the first
two keep name + signature because Instrumentation.java:177 and
ContextImpl.java:476/485 are unchanged). New public forwarders on Activity
because the controller lives in another package:
`performPostCreate`, `performNewIntent`, `atlSetChangingConfigurations`.

### Orderings actually implemented (AOSP 94b4c163b7df anchors)

- Launch (`startActivity`): prev.performPause (only when prev.state >=
  ON_RESUME) + window unfocus → attach → performCreate → early-finish check
  (destroy directly, no start, never saved) → decor add → performStart →
  performRestoreInstanceState (only when saved state exists) → performPostCreate
  → performResume → focus/show → prev.onStop (`moveToState(ON_STOP,false)`;
  the GTK model covered the old activity, so it gets restart/start on return).
- Finish: pause (if resumed) → hand the window back first: unfocus self,
  acquireWindowFocus(next) which resumes the returning activity → own onStop
  (no save: a finishing activity is never saved) → onDestroy → decor remove →
  detach → back sensitivity → close-if-last.
- Recreate (`recreate`): queued single transaction; pause → save (pre-P
  before stop / P+ after stop, `isPreP(targetSdk())`) → stop → destroy with
  finishing=false and changingConfigurations=true → new instance via
  `Activity.internalCreateActivity(className, nw, r.intent)` (the record
  intent is reused) → new record at the SAME index with generation+1 and the
  saved bundle → create(saved) → early-finish check → decor add → start →
  restore → postCreate(saved) → resume → focus only if it was top.
- New intent / clear-top (`resumeActivity`): pop everything above the match
  (pause/stop/destroy/decor remove/detach), then if the target was the
  resumed top: pause → performNewIntent → resume (no recreate); otherwise
  performNewIntent → acquireWindowFocus. The record intent is never
  reassigned and setIntent is never called (ActivityThread.java:6939,
  Activity.performNewIntent Activity.java:9355-9361).
- `closeAll`: pause + unfocus the top, then top-first stop → destroy → decor
  remove → detach for every record; the GTK window stays open.
- Destroy is always a direct `performState(ON_DESTROY)` — never a lifecycle
  path walk (no getLifecyclePath through ON_START/ON_RESUME).

### Saved state

`Activity.onSaveInstanceState` now puts the window hierarchy state as its
FIRST statement (AOSP Activity.java:2613-2620) before
`dispatchActivitySaveInstanceState`; `Window.saveHierarchyState`/
`restoreHierarchyState` already round-trip the same
"android:viewHierarchyState" bundle, so recreate() of an editable view can
restore. No process-death restore is claimed.

### Next verification actions (no build/test was run in this session)

1. Lead rebuild of the lane (`scripts/mobile/build.sh`, JOBS=2). Expected build
   products: javac -h rewrites generated_headers/android_app_Activity.h (the
   four deleted natives drop out of it) and now generates the committed
   generated_headers/android_atl_ATLActivityController.h from the five new
   natives.
2. `tests/activity/run-unit.sh` (A01/A02 path invariants; inputs unchanged —
   the test only drives the static transition engine).
3. lifecycle-probe v2.1 per tests/lifecycle-probe/CONTRACT.md, after
   regenerating the pinned baseline as Session 3 requires (generalize
   make_fixtures.py atl_transform to every scenario with an ActB onCreate and
   add the ActA onRestart framing group on returns), then verify_lifecycle.py.
4. Discriminating cases still owed on top of the probe: double finish(),
   finish-inside-onCreate, recreate() of an activity with an editable view
   restored from the window hierarchy, new intent delivered to a resumed top
   (no recreate, no instance change), and both targetSdk save orderings around
   onStop (27 vs 28).

## Session 5 (lane C) - seam built and unit-green

Commits (branch lifecycle/activity, in order):
- `d8d07766` controller owns lifecycle dispatch; Activity delegates.
- `1e915925` C layer becomes GTK window ops + one host-event seam.
- `e3deb943` startActivity pauses the covered activity only when it is resumed
  (review finding: the guard was `prev.state >= ON_RESUME`, which is also true
  for the ON_PAUSE/ON_STOP/ON_DESTROY ladder values and emitted a second
  onPause for a reentrant launch out of the covered activity's own onPause/onStop).
- `ac8274bc` regenerated JNI headers (javac -h output only).

Build + seam verification (evidence dir
/home/morten/ATL/agent-work/LIFECYCLE-FOUNDATION/activity/seam-v1/):
- `build-seam.log` - scripts/mobile/build.sh, EXIT=0, marker `Built ATL in ...`.
- `symbols.txt` - libtranslation_layer_main.so exports activity_start /
  activity_window_ready / activity_close_all / current_activity_back_pressed
  (signatures unchanged for lane D) plus the five
  Java_android_atl_ATLActivityController_native* entries; the four retired
  Java_android_app_Activity_* natives are gone.
- `unit-path-invariants.log` - tests/activity/run-unit.sh against the rebuilt
  hax.jar: `ACTLPATH ALL_DONE 191 PASS 0 FAIL` (A01/A02 regression + the A06
  path-level unit assertions).

Not yet done (exact next actions, in order):
1. Generalize `atl_transform` in tests/lifecycle-probe to scenario-derived
   observers (Session 3 list) and regenerate baseline-atl.trace with the
   AOSP-alignment rationale beside the artifact.
2. Run lifecycle-probe v2.1 against the real ATL binary (A04) with a
   lane-private ANDROID_APP_DATA_DIR; poll for LIFE_ALL_DONE /
   TERMINAL_THROW_DONE, then verify_lifecycle.py.
3. Probe APKs for A05 (finish-in-callback exactly-once), A07 (new intent on a
   resumed top), A08 (real recreate: distinct instance + restored editable
   state), A09 (saved state, targetSdk 27 vs 28 orderings).
4. A03 single-dispatch code-review evidence (grep: the only native -> Java
   lifecycle entry is controller_host_event), P11 classloader-probe rerun with
   the clean-diff assertion, A10 limitation note.

## Session 6 (lane C) - generalized ATL baseline (A04 preparation)

Commit `06e986d4` (branch lifecycle/activity): `atl_transform` in
tests/lifecycle-probe/make_fixtures.py is now scenario-derived.

- Every scenario that really launches a secondary Activity (S2, S4.1-S4.5,
  S3D1, L-SNAP, L-REGD, L-EQUALS) gets the launcher ordering the controller
  implements: ActA onPause -> ActB onCreate/onStart/onResume -> ActA onStop ->
  ActB onPause -> ActA onRestart/onStart/onResume -> ActB onStop/onDestroy.
  This is AOSP order (ActivityThread.handleLaunchActivity/handleStartActivity/
  callActivityOnStop, TransactionExecutorHelper.getLifecyclePath), not a new
  ATL divergence: the previous baseline modelled the RETIRED C dispatcher
  (ActA onStop before the incoming create, no onRestart).
- Extra groups are accepted by `compare_groups` -> `_validate_extra_group`
  (framing phase, callback_map name, Bundle); manifests event lists unchanged.
  Observer lists are taken from the scenario's own ActA#1 onResume group, so
  L-SNAP keeps AppObs-VICT out, L-REGD includes AppObs-NEW and S4.3 keeps the
  duplicate AppObs-C twice.
- Preamble/S1, the FT-only scenarios and the terminal L-THROW prefix are
  returned unchanged.
- Regenerated artifacts: fixtures/baseline-atl.trace plus the derived fixtures
  and cases.json; rationale in fixtures/baseline-atl.rationale.md; the
  `cross_activity_ordering.divergence` prose in manifest.json updated.
- `bash tests/lifecycle-probe/run_analyzer_selftests.sh` -> ANALYZER SELFTESTS
  OK: 62/62 cases behaved as intended (includes the pinned-baseline trio).

Not yet executed (the A04 real run). Exact next commands from this worktree:

1. Build + sign the probe APK (inside the container; FRAMEWORK_RES must point
   at the LANE framework-res):
   `toolbox run --container atl_dev sh -lc "ATL_WORKSPACE=.../activity/env
    ATL_PREFIX=.../activity/prefix ATL_BUILD_DIR=.../repo/build-mobile
    FRAMEWORK_RES=.../repo/build-mobile/res/framework-res/framework-res.apk
    sh /home/morten/ATL/lifecycle-foundation/activity/repo/tests/lifecycle-probe/build.sh
    <OUTDIR>"`, then sign the aligned APK with
   /home/morten/Android/Sdk/build-tools/37.0.0/apksigner on the host.
2. Run it with the existing runner (it owns xvfb/dbus/marker polling and can
   drive ATL's own close-request through ATL_UI_SCRIPT='wait:...;close:'):
   `ATL_PROBE_SOURCE_DIR=<repo> ATL_PROBE_BUILD_DIR=<repo>/build-mobile \
    ANDROID_APP_DATA_DIR=<lane-private dir> \
    /home/morten/ATL/tools/run-atl-probe.sh <apk> <out> LIFE_ALL_DONE 300`
   then poll/confirm TERMINAL_THROW_DONE, then collect files/trace.log.
3. Splice + verify (the same algorithm as tests/acceptance.sh extract_trace):
   `python3 tests/lifecycle-probe/verify_lifecycle.py \
      tests/lifecycle-probe/fixtures/...-android-reference.trace <spliced-candidate.trace> \
      tests/lifecycle-probe/fixtures/baseline-atl.trace`
   Expected: exit 0 (OK, divergences pinned), a DIVERGENCE line per launching
   scenario, and no BASELINE_MISMATCH. A non-zero exit with BASELINE_MISMATCH
   means the controller ordering and the regenerated baseline disagree: fix the
   controller or the transform, never the manifest.

## Session 7 (lane C) - A03/A05-A10 + P11 executed at HEAD 13db8fe0

Status: every lane-C acceptance row now has executed evidence. Build identity for
all runs below: branch lifecycle/activity, lane build-mobile
`build-mobile/android-translation-layer` sha256
21e17b662ee91a09c66ffcd31c4ae029029e552ec1ad80b8493e57086c82ad55 (mtime
2026-10-15 06:56, 149952 bytes), hax.jar from the same build (meson registers
ATLActivityController), lane-private ANDROID_APP_DATA_DIR, tools only through
resources.py --team activity. Evidence for every command is committed next to the
tests, so the row claims are reproducible from this repo alone.

A04 (already green, commit b88f8217): real lifecycle-probe v2.1 run, 19 scenarios,
LIFE_ALL_DONE + TERMINAL_THROW_DONE, verify_lifecycle.py exit 0 with the 10 pinned
DIVERGENCE lines and no BASELINE_MISMATCH - evidence/a04/.

A05/A06/A07/A08/A09 (commits 13234359 + eb5aac7d): tests/activity/probe (4 activities
driven by the ordinary launcher) run at targetSdk 27, 28 and 35 through
`sh tests/activity/run-probe.sh <apk> <outdir> APROBE_MARK 240`. 34 PASS 0 FAIL in
each run (`APROBE ALL_DONE 34 PASS 0 FAIL` + `APROBE_MARK TERMINAL_DONE`), traces
and results.json in evidence/a05-a09/sdk{27,28,35}/, full container stdout in the
lane evidence dir. Highlights: A05-finish-in-create-{single-instance,never-resumed,
destroy-once,no-save-state,inline-teardown} (A.onPause 53 < C.onCreate 57 <
C.onDestroy 60 < A.onResume 63: finish in onCreate destroys inline, never starts or
saves); A07-nav-order/framing (A.onPause, B.onCreate/onStart/onResume, A.onStop,
B.onPause, A.onRestart/onStart/onResume, A07-return-frame A.onResume < B.onStop <
B.onDestroy) plus a CLEAR_TOP new-intent launch that keeps one instance,
delivers onNewIntent once with the new extra and still reports the record intent
("orig") - no recreate; A08-recreate-distinct-instance (#4 -> #5: old destroyed
once, new created/resumed once, app + view state restored) and
A09-repeated-recreate-{distinct,clean,restored} (#5 -> #6: R#5.onDestroy=1,
R#6.onCreate=1, R#5.onStart=0, state restored again); A09-save-order-targetSdk
shows the documented targetSdk split on real runs: 27 saveIdx 89 < stopIdx 92
(save-before-stop), 28/35 stopIdx 89 < saveIdx 92 (stop-before-save).
A06 has both halves: the runtime pair (hasWindowFocus false during onResume,
true after the focus event; onWindowFocusChanged framing contains zero lifecycle
callbacks) and the unit suite FocusInvariants (`ACTLFOCUS ALL_DONE 9 PASS 0 FAIL`,
evidence/a06/unit-focus-invariants.log, run through tests/activity/run-unit.sh on
the lane dalvikvm - PathInvariants `ACTLPATH ALL_DONE 191 PASS 0 FAIL` re-verified
in the same run).

A03 (commit 51daa55e): evidence/a03-single-dispatch.txt - one dispatcher
(hostEvent + the single controller + the one transition table) with negative greps
for a second launcher/service path; the four C entry points main.c/back_button.c
consume are unchanged and each is one host event.

A10 (commit 51daa55e): evidence/a10-no-process-death-restore.txt - the seam is
in-process state only (no persisted Bundle, no restart path; architecture section
8 boundary), and every probe run emits APROBE_INFO A10-limitation so no test can be
read as claiming a process-death restore.

P11 (commits 5a635950 + 13db8fe0): `sh tests/activity/run-classloader-probe.sh <out>`
on the lane candidate - ATL-PROBE STATUS=OK exit=0 marker=CLPROBE_JSON_END,
CLPROBE VERDICT=PASS platform=atl run_id=atlrun-1792042375-347043 checks=41
passed=41 failed=0 with --expect-loader-source own-classloader, matching the pinned
Firefox baseline. Clean-diff assertion: the lane diff (34 files) contains no
art/loader/bionic/environ file and adds no FindClass or class-visibility workaround.
Evidence (verifier.log/runner.log/results.json) in evidence/p11/.

Row status: A03 COMPLETE (static), A04 COMPLETE, A05 COMPLETE, A06 COMPLETE,
A07 COMPLETE, A08 COMPLETE, A09 COMPLETE, A10 COMPLETE (documented limitation, no
over-claim), P11 COMPLETE for the lane candidate.

Exact next action (integration, not lane C):
1. Cherry-pick lane C in order onto combined (seam commits d8d07766..2711907a, then
   13234359, 5a635950, eb5aac7d, 51daa55e, 13db8fe0) - the test/evidence commits are
   inert for the runtime.
2. Rerun P11 against the combined build with the same script (ATL_LANE_DIR/
   ATL_RESOURCES now overridable): that combined-HEAD run is the gate, because the
   lane run cannot cover other lanes' loader input.
3. Optional combined-only rerun of run-probe.sh per targetSdk to confirm the merged
   tree still reports 34 PASS 0 FAIL.

Lane-C limitations (documented, not over-claimed): the probe is one APK with
explicit-component launches; there is no Instrumentation-driven or manifest-ordered
scenario, no process-death restore, and no multi-window/focus-loss scenario beyond
the recreate window sampled in A06.

## Session 8 (lane C) - A04 external close really fires; runner bug corrected

Session 7's A04 artifact set was honest but incomplete: its `close.log` read
"Authorization required ... cannot open DISPLAY ':77'", so the external window
close never reached the app, scenario S3D2 stayed open
(`LIFE_SCEN_BEGIN S3D2` with no `LIFE_SCEN_END`, 475 LIFE_EVT) and the runner
killed ATL. The window-close path (main.c app_exit -> activity_close_all ->
hostEvent HOST_EVENT_WINDOW_CLOSE_REQUEST -> closeAll) therefore had no executed
evidence at all, and neither did `closeAll()` itself.

Root cause (harness, not ATL): run-probe.sh discovered the X session with
`pgrep -f "android-translation-layer" | head -1`. Other lanes leave their own ATL
processes and live Xvfb sessions in the shared container, so the first match was
another lane's process; the close was posted to that lane's display (the window
that disappeared was titled `org.atl.tests.audio`, not this lane's
`org.atl.tests.activity.probe`). Isolation: `tests/activity/gtk-close-probe.c`
(a GTK-only 40-line program, same window type as ATL's, driven by
`tests/activity/gtk-close-probe.sh`) proves GTK4 on this Xvfb, with and without a
window manager, turns an external WM_DELETE_WINDOW into `close-request` and lets
g_application_run() return - so the environment was never the problem.

Fix (committed with this section):
- run-probe.sh matches this run's APK (`[a]ndroid-translation-layer.*<apk bn>`)
  both for the liveness poll and for the DISPLAY/XAUTHORITY discovery.
- the sourceless `atl-x11-close` binary is replaced by
  `tests/activity/x11-wm-close.c` (source-available WM-protocol sender, built into
  the lane's private probe dir) - `xdotool windowclose` is not equivalent: this
  container's build destroys the window instead of delivering the message.
- run-probe.sh gained `ATL_PROBE_WM=<wm>` (production ATL always has a WM; Xvfb
  alone has none) and no longer trips `set -u` on an unset ATL_PROBE_WM.

Rewritten A04 evidence (evidence/a04/, same artifact names, all run-produced):
495 LIFE_EVT, `LIFE_SCEN_BEGIN S3D2` (487) .. `LIFE_SCEN_END S3D2` (538); the 20
extra events are the close path - ActB#11 onStop/onDestroy then ActA#1 onDestroy,
i.e. closeAll() ran from the GTK close-request; then `System.exit called,
status: 0` and the runner's APK-scoped liveness poll sees the process gone
immediately (atl_exited_after=0s). verify_lifecycle.py is byte-identical to
session 7's output: exit 0, the same 10 pinned DIVERGENCE lines, no
BASELINE_MISMATCH, in the same order - the stricter scenario does not disturb any
other scenario. Identity: binary 21e17b66 (unchanged session-7 lane build), apk
7b61db40, reference fixtures/reference-v2.1.trace, pinned fixtures/baseline-atl.trace.

Row status after Session 8: A03-A10 + P11 COMPLETE, and A04 additionally covers
the externally driven close path. Lane C now has executed evidence for every host
event it owns (start, back, window-ready/focus, window-close/closeAll).

Next actions for integration (unchanged, still not lane C):
1. Cherry-pick lane C in order onto combined (seam d8d07766..2711907a, then
   13234359, 5a635950, eb5aac7d, 51daa55e, 13db8fe0, then this session's
   evidence/tooling commit); the test/evidence commits are inert for the runtime.
2. Rerun P11 against the combined build with run-classloader-probe.sh - that
   combined-HEAD run is the gate.
3. Optional combined-only rerun of run-probe.sh per targetSdk (expect the same
   34 PASS 0 FAIL) plus one ATL_PROBE_EXTERNAL_CLOSE=yes A04 rerun to confirm the
   merged tree still closes and exits.
