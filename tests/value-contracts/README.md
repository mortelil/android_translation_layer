# Value API and queue contracts

Run `sh tests/value-contracts/build.sh BUILD_DIR OUTPUT_DIR` inside Alpine.
Sign OUTPUT_DIR/values.apk with a test key, then launch through
`scripts/mobile/run.sh` with ATL_BUILD_DIR selecting the candidate build and
ANDROID_APP_DATA_DIR/XDG_CACHE_HOME pointing to a fresh test directory.
A successful run prints `PASS values checks=57` and exits zero. Any assertion
fails the run. Halt is used only after all assertions because the old standalone
ART used by the developer runtime has an unrelated teardown failure.

Coverage: typed and unset Keyframes, cloning/interpolators, AudioFormat defaults,
copy/equality/validation, output and input index-mask conversion (no audio device
opened), aggregate keyboard source consistency, empty/future/due/removed queue
messages and synchronization barriers. Queue tests run on their own Looper.
These are contract tests, not claims that media playback or Firefox works.

Keyframe.java is copied byte-for-byte from AOSP frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/animation/Keyframe.java
(android-17.0.0_r1; matches installed Android 37 SDK sources). Apache header kept.
MessageQueue.isIdle uses that tree's legacy queue semantics. AudioFormat is the
OMP port from Android 37 SDK sources, with its Apache header retained.

Also covers AudioFormat parcel round-trip, View traversal ID state and cache
flag invalidation, and truthful absence of autofill/widget pinning services.
Traversal IDs do not imply screen-reader traversal integration.

The View overload of AutofillManager.notifyValueChanged is harmless when no
autofill feature exists, including a null argument: AOSP android-15.0.0_r1
core/java/android/view/autofill/AutofillManager.java returns at hasAutofillFeature
before reading the View. This prevents MaterialCheckBox construction from
crashing Firefox's tab-crash UI; it does not repair the original Gecko crash.
