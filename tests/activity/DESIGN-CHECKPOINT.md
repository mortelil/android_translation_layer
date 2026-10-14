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
