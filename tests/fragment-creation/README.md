# Headless fragment creation

Inside atl_dev, build with `sh tests/fragment-creation/build.sh BUILD OUTPUT`.
Run OUTPUT/fragment-creation.apk with scripts/mobile/run.sh, an explicit
ATL_BUILD_DIR and private ANDROID_APP_DATA_DIR. Require FRAGMENT_CREATION_PASS
and exit 0. The probe covers stable manager identity, immediate and queued
creation transactions, onActivityCreated after the activity override returns,
late creation catch-up and no duplicate callbacks when the pending queue drains.
The old runtime fails manager identity; the patched runtime passes.

This does not implement fragment views/back stacks, saved-state restoration,
or complete late start/resume/removal transitions. The implementation follows
the creation boundary in AOSP Activity.performCreate (android-17.0.0_r1,
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d). Existing application callback timing
is preserved. ReVanced reaches a later, separate configuration recursion.
