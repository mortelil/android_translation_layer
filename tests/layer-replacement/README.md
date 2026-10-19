# Layer replacement contract

Build in Alpine with `sh tests/layer-replacement/build.sh build-mobile OUT`.
Launch OUT/probe.apk with the candidate ATL runtime and a private app-data
profile. Sign and install the same APK on an Android emulator to compare.
Success: `LAYER_REPLACEMENT_PASS checks=18` (Logcat tag LayerReplacement).

Checks cover replacement identity, retained layer ID and bounds, callback
ownership including self replacement, ID lookup, padding refresh, null removal,
null-safe bounds/state/visibility/intrinsic-size/drawing operations, constant-state
cloning and mutation, actual replacement pixels, and invalid indices.

The AOSP reference is frameworks/base commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, graphics/java/android/graphics/drawable/LayerDrawable.java.
Notably, clearing a layer preserves its cached padding on Android; the test
checks this observed behavior rather than assuming it should become zero.

Verified on Android emulator and ATL x86_64: 18 checks each. This fixes the
missing setDrawable method used by ReVanced's UI; it does not establish video
playback or repair ReVanced's separate TLS socket failure. Existing unrelated
LayerDrawable gaps (e.g. layer gravity and explicit sizing) are outside scope.
