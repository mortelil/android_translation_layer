row A04 - ATL lifecycle-probe v2.1 real run (executed)

artifacts (all produced by the run below, not hand-written):
  atl-stdout.log          container stdout of the ATL launch (LIFE_TRACE_BEGIN..TERMINAL_THROW_DONE)
  candidate-atl-app.trace app-written files/trace.log, the canonical candidate trace
  results.json            app-written aggregate (pre-external-close scenarios)
  verify.log              verify_lifecycle.py output: exit 0, 10 DIVERGENCE lines, no BASELINE_MISMATCH
  close.log               note: the external WM_DELETE_WINDOW helper could not attach (Xvfb auth);
                          ATL exited through its own window/close path after LIFE_ALL_DONE

run:
  apk     tests/lifecycle-probe/build.sh (lane FRAMEWORK_RES) + apksigner debug key
  launch  toolbox atl_dev: ATL_WORKSPACE/ATL_PREFIX/ATL_BUILD_DIR=lane, ANDROID_APP_DATA_DIR lane-private,
          ulimit -c 0, dbus-run-session -- xvfb-run -n 77 -s "-screen 0 1280x1024x24" sh scripts/mobile/run.sh <apk>
  markers LIFE_ALL_DONE then TERMINAL_THROW_DONE, 475 LIFE_EVT, 448 aggregate events, 0 exceptions
  verify  python3 tests/lifecycle-probe/verify_lifecycle.py <android reference trace> \
            candidate-atl-app.trace tests/lifecycle-probe/fixtures/baseline-atl.trace   -> exit 0
