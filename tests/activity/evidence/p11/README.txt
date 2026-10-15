row P11 - Firefox classloader probe (5418fb3) rerun green + clean-diff assertion

Method (read-only on the Firefox repo; lane-C adaptation of the documented
classloader-reference-r3 ATL runner):
  sh tests/activity/run-classloader-probe.sh <outdir>
    - hardlinks the EXACT Firefox fixture APK
      (/home/morten/ATL/parallel-apps-ab/firefox/fixtures/apk/firefox.apk,
      sha256 6166ad9d...c14f776) as <outdir>/firefox-extra-opaque and asserts the
      inode/sha256 identity, then attaches it via ATL_PROBE_EXTRA_APKS (the
      opaque-name path the split-APK loader requires for a second base APK);
    - probe APK data/classloader-probe/build3/clprobe-v1.apk
      sha256 216411e2...60ccc175 (unchanged, read from the Firefox tree);
    - runs it as the BASE app under THIS lane's candidate
      (ATL_LANE_ROOT=/home/morten/ATL/lifecycle-foundation ATL_LANE=activity,
       lane repo/build-mobile + lane prefix + lane-private app data,
       resources.py --team activity --kind atl);
    - verifies with the Firefox verifier:
        python3 <firefox>/repo/tests/classloader-probe/verify_classloader.py \
            <outdir>/app-results.json --platform atl --expect-loader-source own-classloader

Result (lane HEAD 2711907a, lane binary sha256 21e17b662ee91a09...):
  ATL-PROBE STATUS=OK exit=0 marker=CLPROBE_JSON_END elapsed=11s
  CLPROBE VERDICT=PASS platform=atl run_id=atlrun-1792042375-347043 checks=41 passed=41 failed=0
  verifier.log  (copy in this directory) - 41/41 PASS, verdict.aosp_contract_satisfied PASS
  runner.log    - container launch + marker evidence
  results.json  - app-written aggregate checked by the verifier
  Full run dir: /home/morten/ATL/agent-work/LIFECYCLE-FOUNDATION/activity/p11/
Same contract as the pinned Firefox baseline (ATL PASS 41/41, own-classloader),
so lane C did not regress frameless native-thread FindClass behaviour.

Clean-diff assertion (no loader visibility workaround anywhere in lane C):
  $ git diff --name-only ab7345cf..HEAD | grep -ci "art/|classloader|bionic|environ"
    0        (34 changed files: Activity/ATLActivityController/Window/View/TextView/
              android_app_Activity.c/handle_cache*/meson/tests only)
  $ grep -rn "FindClass" <lane diff files> -> no new FindClass or
    class-visibility change; no ART/Bionic or runtime-wide loader patch.

Combined-HEAD note: this run used the lane candidate because the combined
worktree still sits at ab7345cf (no lane commits merged yet). The integration
owner must re-run the identical script against the combined build at GATE; the
lane diff above is the evidence that the combined run has no lane-C loader
input to change.
