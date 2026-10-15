row A04 - ATL lifecycle-probe v2.1 real run (executed), external close included
=============================================================================

This artifact set is from a run whose GTK window is closed from OUTSIDE the
process while the probe is waiting, so scenario S3D2 (external normal close) is
really driven and closed: the trace now contains both `LIFE_SCEN_BEGIN S3D2`
(line 487) and `LIFE_SCEN_END S3D2` (line 538).

Identity
  flow      ATLActivityController seam (branch lifecycle/activity @ 9c0e496e)
  binary    <lane>/repo/build-mobile/android-translation-layer
            sha256 21e17b662ee91a09c66ffcd31c4ae029029e552ec1ad80b8493e57086c82ad55
  probe apk lifecycle-probe-a04.apk
            sha256 7b61db4079dc273cc8f984dd720bc4d65f18157bce994040f48532c7b05439ba
  reference tests/lifecycle-probe/fixtures/reference-v2.1.trace
  pinned    tests/lifecycle-probe/fixtures/baseline-atl.trace

artifacts (all produced by the run below, not hand-written)
  atl-stdout.log           container stdout of the ATL launch
                           (LIFE_TRACE_BEGIN..LIFE_ALL_DONE..TERMINAL_THROW_DONE)
  candidate-atl-app.trace  app-written files/trace.log, the canonical candidate trace
  results.json             app-written aggregate: 448 events, 0 exceptions
  verify.log               verify_lifecycle.py output: exit 0, 10 DIVERGENCE lines,
                           no BASELINE_MISMATCH
  close.log                the external close: DISPLAY/XAUTHORITY of this run and
                           `x11-wm-close: sent WM_DELETE_WINDOW to 0x200005`

run (lane runner, one command; builds the lane-private WM-protocol sender first)
  ATL_PROBE_EXTERNAL_CLOSE=yes sh tests/activity/run-probe.sh \
      <lane-apk>/lifecycle-probe-a04.apk <outdir> LIFE_ALL_DONE 300
  python3 tests/lifecycle-probe/verify_lifecycle.py \
      tests/lifecycle-probe/fixtures/reference-v2.1.trace \
      <outdir>/trace.log tests/lifecycle-probe/fixtures/baseline-atl.trace

observed
  - 495 LIFE_EVT lines; the 20 events after the former tail are the close path:
    ActB#11 onStop/onDestroy then ActA#1 onDestroy, i.e. controller.closeAll()
    ran from the GTK close-request (hostEvent HOST_EVENT_WINDOW_CLOSE_REQUEST).
  - `System.exit called, status: 0`; the runner's APK-scoped liveness poll sees
    the ATL process gone immediately after the close (atl_exited_after=0s).
  - verify result is byte-identical to the earlier (non-strict) artifact set:
    same 10 pinned DIVERGENCE lines, no BASELINE_MISMATCH.

history (why this file set replaced the previous one)
  The first A04 artifact set had `close.log` = "Authorization required ... cannot
  open DISPLAY ':77'": the external close never attached, so S3D2 stayed open
  (`LIFE_SCEN_BEGIN S3D2` with no END, 475 events) and the app was killed by the
  runner. The cause was the runner's X-session discovery
  (`pgrep -f android-translation-layer | head -1`), which in this shared
  container selected another lane's stale ATL process and sent the close to that
  lane's Xvfb display (observed closing a window titled `org.atl.tests.audio`
  while this lane's app never saw the message). run-probe.sh now matches this
  run's APK (`[a]ndroid-translation-layer.*<apk basename>`) and uses the
  source-available sender tests/activity/x11-wm-close.c; the fix and the
  diagnostic that isolated it (tests/activity/gtk-close-probe.sh, GTK-only)
  are committed with this evidence.