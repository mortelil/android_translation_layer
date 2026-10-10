# Verification — 2026-10-10

Base: main 647b89eb, including the separately merged Paint and PiP-query fixes.
Only Fragment.class and the new Fragment$InstantiationException.class differ in
hax.jar from the baseline. No lifecycle/tag/native font implementation is included.

- Same signed contract APK: Android API 37, ATL x86 and Nura ARM PASS.
  Baseline fails with the missing class. Tests cover exact superclass, constructor,
  message, cause identity, catch through AndroidRuntimeException and nulls.
- x86 build in Alpine Toolbox atl_dev; mobile-smoke PASS; Paint 64-case and PiP
  create/resume probes PASS. FONT-001/002 observations unchanged from baseline,
  including pre-existing bounds/metric failures (not relabeled as passing).
- Immich PC: photo/back/library/scroll checked. Nura: thumbnails and library
  navigation checked with approved private login copy. The final full-photo
  capture stayed on the grid, so that step is NOT counted as verified for this
  patch; it was verified on the preceding baseline. No photo mutation/upload.
- Messenger PC and Nura: visible login fields, synthetic input/deletion, password
  masking; PC language selector. No login submission. Existing PC background
  Notification.Builder.getNotification failure remains a known limitation.
- Plexamp Nura: fresh profiles for baseline and candidate; visually confirm
  welcome -> login BEFORE triggering synthetic input, then verify retained text,
  deletion and masking. Both PASS. Icon-font tofu remains. Earlier attempts with
  overlapping processes or a reused ambiguous profile were discarded. No x86 APK.
- LocalSend PC/Nura: send/receive/settings navigation and synthetic text selection;
  PC-to-Nura text visibly received. File-picker and reverse file transfer are not
  covered. Video/audio and physical OSK interaction were not retested.

The ARM overlay uses the freshly built unchanged baseline native binaries and
this exact candidate DEX jar; explicit ATL_BUILD_DIR, hashes and process maps
identify the tested runtime. Installed Shelf runtime and original source remain
untouched. Full logs and screenshots are private, not committed.

SHA-256:
- api-impl.jar: 59de1191b56bf73d497834aa2e7d5bcc93d72dc8580e50f00e0e37a49aa3ea18
- signed.apk: a9a07b0a49f4035d0a7d446727a2b96472fae4921ff9c8fc1d025beb2650506c
