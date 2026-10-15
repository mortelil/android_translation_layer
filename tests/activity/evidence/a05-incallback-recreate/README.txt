row A05 (second half) - recreate() inside onResume, executed ATL probe run
=========================================================================
ADDITIVE to evidence/a05-a09/: that artifact set stays valid for its own probe
revision, nothing in it was overwritten or re-run.

Why this set exists
-------------------
acceptance row A05's frozen verification method names TWO cases:
  "probe: recreate() inside onResume; finish() inside onCreate; exactly one
   destroy, no double transitions"
evidence/a05-a09/ executed the finish() half (A05-finish-in-create-*, 6 checks)
and the recreate() half only in the DEFERRED form: the probe driver posts every
action with `later(...)` = Handler.postDelayed(150ms) (class javadoc: "each
action is posted with a short delay instead of being re-entered from inside the
callback"), so recreate() ran ~150 ms AFTER onResume had returned. The
in-callback reentrancy/implicit-transaction path was therefore NOT exercised by
any run. This artifact set adds exactly that case and nothing else.

Identity
--------
flow        ATLActivityController seam, branch lifecycle/activity
lane HEAD   8fd1c20b + this additive probe change (probe sources changed, test
            code only; no src/ runtime file was touched, so the ATL binary is
            the same session-7 build)
binary      <lane>/repo/build-mobile/android-translation-layer
            sha256 21e17b662ee91a09c66ffcd31c4ae029029e552ec1ad80b8493e57086c82ad55
            (149952 bytes, mtime 2026-10-15 06:56)
probe       org.atl.tests.activity.probe, PROBE_ID atl-activity-seam-probe-v2
            (= v1 + the S5 in-callback recreate step; no other change)

Method (reproducible, all through resources.py --team activity)
--------------------------------------------------------------
build   python3 <agent>/resources.py --team activity --kind build --timeout 900 -- \
          toolbox run --container atl_dev sh -lc \
          "FRAMEWORK_RES=<lane>/repo/build-mobile/res/framework-res/framework-res.apk \
           sh <lane>/repo/tests/activity/probe/build.sh <targetSdk> <out>/sdk<targetSdk>"
sign    /home/morten/Android/Sdk/build-tools/37.0.0/apksigner sign \
          --ks /home/morten/ATL/font-tests/debug.keystore --ks-key-alias fontprobe \
          --ks-pass pass:android --key-pass pass:android \
          --out <lane>/probe-build/v2/activity-probe-v2-sdk<targetSdk>.apk \
          <out>/sdk<targetSdk>/activity-probe-sdk<targetSdk>-aligned.apk
        (the keystore/cert is the same one evidence/a05-a09 used: CN=ATL Font
         Probe, SHA-256 C4:03:...:507E; re-signing the aligned APK reproduces
         the committed sha256 byte-for-byte, so the APK identity is checkable)
run     ATL_LANE_DIR=<lane> sh <lane>/repo/tests/activity/run-probe.sh \
          <apk> <lane-evidence>/a05-incallback-recreate/sdk<targetSdk> APROBE_MARK 240
        (container atl_dev, lane ATL_WORKSPACE/ATL_PREFIX/ATL_BUILD_DIR, lane
         private ANDROID_APP_DATA_DIR, dbus-run-session + xvfb-run -a, ulimit -c 0;
         routed through resources.py --team activity --kind atl)
probe launch = the ordinary launcher (createMainActivity -> activity_start ->
hostEvent(HOST_EVENT_START)); every assertion runs on the main looper from real
Activity callbacks, i.e. the production controller path.

Results (all three targets, same claim)
---------------------------------------
  targetSdk 27: APROBE ALL_DONE 41 PASS 0 FAIL + APROBE_MARK TERMINAL_DONE
  targetSdk 28: APROBE ALL_DONE 41 PASS 0 FAIL + APROBE_MARK TERMINAL_DONE
  targetSdk 35: APROBE ALL_DONE 41 PASS 0 FAIL + APROBE_MARK TERMINAL_DONE
  41 = the 34 checks a05-a09 already counted + the 7 A05-incallback-recreate
  checks below (the v1 34 all still PASS; the full v1 49-entry list is present
  and unchanged - only 10 entries were added, 7 checks + 3 S5 info records).
  container stdout per target (marker poll, liveness, launched APK path) lives
  in <lane-evidence>/a05-incallback-recreate/sdk<targetSdk>/atl-stdout.log.

APK sha256 (lane-private probe-build/v2/, signed)
  27 8250205612274848099b6f440777f424b30545956f7d19ae9697ae4359538e72
  28 ba38306b258b6c9a727d9f935bc99ba53538fe43ca112c90e9aa7138047c7c70
  35 0127781f7815f5c16cb0a2278b174a7f953f613770a5a10a16bf53358aa8f61c
artifact sha256 (this dir)
  sdk27/results.json      5e7c3575fa0b4aca33ac5c6269977844beea717d358a20880098e7ba404f614e
  sdk27/aprobe-trace.log  54ba12d05560fc27fffd87968055f364bd144fabc44519bec0e24b48183deba7
  sdk28/results.json      135157189606616ce927d419be2a193796a8f036d50149d43571cf07bb98a806
  sdk28/aprobe-trace.log  70c4a06a3e4ca99f3f88656de6e1a649993522c316725a095dbbdddfbd90a456
  sdk35/results.json      cf91fc2a40faa7a8d5c05bb18353fe5c2c167300b9a7af8bd1f667abceffa9eb
  sdk35/aprobe-trace.log  7080307e16441752a2885732ab825cd23ea30835316ca4ed5d2f227c09abcd8a

The scenario (S5-RECREATE-IN-CALLBACK) and what the run really did
------------------------------------------------------------------
ActQ instance #1 calls recreate() synchronously right after super.onResume(),
i.e. while the controller's resume of that very instance is still on the Java
stack. A static instance counter guarantees only instance #1 recreates (a second
instance must not, or the relaunch would loop).

sdk27 trace, APROBE_EVT labels (the check details print the trace's 0-based
event index, which is this label - 1), ActA#1 onPause at 205/207:
  208/210 ActQ#8 onCreate            (208 pre, 210 post)
  211/213 ActQ#8 onStart
  214/215 ActQ#8 onPostCreate
  216/218 ActQ#8 onResume (pre/post) + 217 CB onActivityResumed
  --- recreate() called here, inside that same onResume frame ---
  223/225 ActQ#8 onPause
  226/228 ActQ#8 onSaveInstanceState (post = nonnull)
  229/231 ActQ#8 onStop
  232/234 ActQ#8 onDestroy
  235/237 ActQ#9 onCreate            (nonnull -> the saved bundle was handed on)
  238/240 ActQ#9 onStart
  241/242 ActQ#9 onRestoreInstanceState
  243/244 ActQ#9 onPostCreate
  245/247 ActQ#9 onResume
  219 ActQ#8 onWindowFocusChanged(true); 248 ActQ#9 onWindowFocusChanged(true)
So the queued relaunch transaction ran once, after the in-flight resume
completed: resume -> save -> stop -> destroy(old) -> create/restore(new) ->
resume. No re-entry, no duplicate, one save, distinct instances.

Row -> check mapping (all PASS in all three runs)
  A05-incallback-recreate-distinct-instance      old=ActQ#8 new=ActQ#9
  A05-incallback-recreate-old-resumed-once       old onResume count=1
  A05-incallback-recreate-old-destroyed-once     old onDestroy count=1
  A05-incallback-recreate-new-created-once       new onCreate count=1
  A05-incallback-recreate-new-resumed-once       new onResume count=1
  A05-incallback-recreate-not-reentrant          the old instance's onResume
     post-super index < its own first onPause index AND < the new instance's
     first onCreate index (sdk27: 217 < 222 and 217 < 234). This is the check
     that fails if a relaunch ever re-enters the in-flight resume (a re-entrant
     teardown would have to appear between onResume.pre and onResume.post).
  A05-incallback-recreate-no-double-transition   exactly one each of
     onCreate/onStart/onResume/onPause/onStop/onDestroy for the old instance and
     one each of onCreate/onStart/onResume for the new one (no duplicate or
     stale transition of either instance)
  S5-Q1-resumed / S5-Q2-resumed / S5-lifecycle-window (info)
     the S5 lifecycle window as the probe saw it: ActA#1 onPause, ActQ#8
     create/start/postCreate/resume, ActA#1 onStop, ActQ#8
     pause/save/stop/destroy, ActQ#9 create/start/restore/postCreate/resume.

Scope of this claim
  Real FileHost/ATL path (ordinary launcher, real GTK window, production
  ATLActivityController), executed failure-surface included: the same probe also
  drives finish() inside onCreate, external window close, clear-top new intent
  and repeated recreate in the same runs. Single-platform (x86_64, see A04/V04);
  no Instrumentation-driven, manifest-ordered or process-death scenario is
  claimed. The probe sources for this run are committed with the evidence
  (probe-diff.patch records the additive change).