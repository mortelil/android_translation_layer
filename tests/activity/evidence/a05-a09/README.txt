rows A05/A07/A08/A09 (+A06 runtime, +A10 boundary) - executed ATL probe runs

Method (reproducible):
  build   FRAMEWORK_RES=<lane>/repo/build-mobile/res/framework-res/framework-res.apk \
            sh tests/activity/probe/build.sh <targetSdk> <outdir>   (3 SDKs) + apksigner
  run     sh tests/activity/run-probe.sh <apk> <outdir> APROBE_MARK 240
          (toolbox atl_dev, lane ATL_WORKSPACE/ATL_PREFIX/ATL_BUILD_DIR,
           lane-private ANDROID_APP_DATA_DIR, dbus-run-session + xvfb-run -a,
           ulimit -c 0; verified by resources.py --team activity --kind atl)
  probe   org.atl.tests.activity.probe.Probe via the ordinary launcher
          (createMainActivity -> activity_start -> hostEvent(HOST_EVENT_START));
          every assertion runs on the main looper from real Activity callbacks.
  markers "APROBE ALL_DONE <p> PASS <f> FAIL" then "APROBE_MARK TERMINAL_DONE"
          in every trace; results.json is the same aggregate the probe prints.

Results (candidate repo HEAD 2711907a, lane build-mobile 2026-10-15):
  targetSdk 27: 34 PASS 0 FAIL   evidence/a05-a09/sdk27/{results.json,aprobe-trace.log}
  targetSdk 28: 34 PASS 0 FAIL   evidence/a05-a09/sdk28/{results.json,aprobe-trace.log}
  targetSdk 35: 34 PASS 0 FAIL   evidence/a05-a09/sdk35/{results.json,aprobe-trace.log}
  container stdout + marker poll for each: <lane-evidence>/a05-a09/sdk<NN>/atl-stdout.log
  APK sha256: evidence/a05-a09/apk-and-artifact.sha256 (sdk27; 28/35 differ only in
  the manifest targetSdk byte range -> distinct aapt2/apksigner inputs, same sources)

Row -> assertion mapping (all green in all three runs):
  A05  A05-finish-in-create-single-instance      finish() inside ActC.onCreate -> one C record
       A05-finish-in-create-never-resumed        onStart=0 onResume=0 for C
       A05-finish-in-create-destroy-once         C onDestroy=1 (inline destroy, not a path walk)
       A05-finish-in-create-no-save-state        C onSaveInstanceState=0
       A05-finish-in-create-inline-teardown      A.onPause < C.onCreate < C.onDestroy < A.onResume
       A05-finish-in-create-a-resumed-once       A.onResume=1 A.onDestroy=0 in the scenario window
  A06  A06-runtime-focus-after-resume            hasWindowFocus()==true once the GTK focus
                                                 event has arrived (sampled 250ms after onResume)
       A06-focus-not-yet-at-resume               hasWindowFocus()==false DURING onResume -
                                                 AOSP order (resume then window focus)
       A06-runtime-focus-events (info)           onWindowFocusChanged count in the recreate
                                                 window (1); the focus window itself contains
                                                 ZERO lifecycle callbacks (focus != lifecycle)
       unit half: evidence/a06/unit-focus-invariants.log (ACTLFOCUS 9 PASS 0 FAIL, run at the
       same HEAD through tests/activity/run-unit.sh)
  A07  A07-result-request-code / -code-and-data / -delivered-once
       A07-single-B-instance                     one ActB record for the A->B launch
       A07-nav-framing + A07-nav-order           A.onPause,B.onCreate,B.onStart,B.onResume,
                                                 A.onStop,B.onPause,A.onRestart,A.onStart,A.onResume
       A07-return-frame                          A.onResume < B.onStop < B.onDestroy
       A07-observer-created-once                 Application observer onCreate once
       A07-new-intent-single-instance / -delivered-once / -payload / -record-intent-preserved
       A07-new-intent-no-recreate                FLAG_ACTIVITY_CLEAR_TOP: onCreate=1 (the launch),
                                                 onNewIntent=1, no second instance, getIntent() kept "orig"
       A07-explicit-component-launch             startActivityForResult uses setClass (ATL contract)
  A08  A08-recreate-distinct-instance            old=ActR#4/5 new=ActR#5/6 (different objects)
       A08-recreate-old-destroyed-once / -new-created-once / -new-resumed-once
       A08-recreate-save-state-once
       A08-recreate-restored-app-state           app-saved field restored (v1)
       A08-recreate-restored-view-state          TextView hierarchy text restored "typed"
  A09  A09-save-order-targetSdk                  sdk27 save-before-stop (idx 89<92, pre-P rule)
                                                 sdk28/35 stop-before-save (89 idx 92>89, P+ rule)
       A09-repeated-recreate-{distinct,clean,restored}
                                                 second recreate of the already-recreated
                                                 instance: R#5 onDestroy=1, R#6 onCreate=1,
                                                 R#5.onStart=0, state restored again
       A09-recreate-a-resumed-once
       plus the A->B->back, finish-during-startup, new-intent, result-delivery and
       editable-view-state scenarios above (same probe, one run per targetSdk).
  A10  APROBE_INFO A10-limitation in every trace + tests/activity/evidence/a10-no-process-death-restore.txt
