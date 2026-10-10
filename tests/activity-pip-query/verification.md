# PiP query verification — 2026-10-10

Base: main 17c8ec7b (the separately merged Paint rounding patch).
The candidate adds only Activity.isInPictureInPictureMode returning false;
there is no enter-PiP path in ATL. The broader lifecycle callback patch is absent.

- Same signed APK: Android API 37 PASS in onCreate/onResume; current main FAIL
  with NoSuchMethodError; candidate x86 and ARM PASS in both phases.
- mobile-smoke: PASS on candidate; baseline had passed in the preceding patch.
- Paint 64-case probe: PASS. FONT-001/002 JSON is identical to the main baseline,
  including its explicitly recorded existing failures.
- Immich PC: photo/back/library/scroll checked; photo/library PNGs byte-identical
  to main. Nura: photo/back/library checked on a private approved login copy.
- Plexamp Nura: welcome-to-login, synthetic insertion/deletion/masking PASS;
  icon-font tofu remains. No x86 APK exists.
- Messenger Nura: login layout and synthetic input/deletion/masking PASS.
  PC: both fields typed (password visibly masked), cleared, language selector
  opened. The earlier baseline Notification.Builder.getNotification failure is
  a known limit, not a behavior this patch repairs.
- LocalSend: receive/send/settings and synthetic text selection checked on PC
  and Nura. Synthetic text sent PC-to-Nura and visibly received. File-picker and reverse-file-transfer coverage remains incomplete.
- No real login, uploads, server-side photo edits or personal file transfer.

Runtime identity: x86 built in atl_dev in a separate integration worktree.
ARM uses the native baseline freshly built from the archived main source, with
this candidate's exact Java DEX jar in a private overlay. Process maps recorded.
Only Activity has changed code/constants in hax.jar; regenerated internal R
classes differ in non-code metadata, with identical source and javap code/constants.
Generated Manifest/R files are not staged. Screenshots and full logs stay private.

Artifact SHA-256:

- build-pip/api-impl.jar: `e8462bda836579bc3ac60fbe74ab22be7c17c760603e20237167c4f9600db60c`
- pip-probe/signed.apk: `9ec3f9cfbb437d4274e60947fbdd87ce67c81b0862d966cd85f845f44d824d41`
