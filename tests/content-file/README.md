# Content file opens

Build in Alpine: `sh tests/content-file/build.sh BUILD_DIR OUTPUT_DIR`, sign,
then launch org.atl.contentfile/.Probe. Require CONTENT_FILE PASS and
files/results.json. The test reads/writes only its own fixture. It checks
file/content URIs, signal forwarding, cancellation before and during the
provider call, missing providers, bad modes and real truncation.

Adds the openFile alias identified in the OMP Sketchbook lane, and repairs
ATL's existing ignored cancellation argument. The provider's default overload
preserves old two-argument implementations. A local file URI remains a
synchronous open, matching AOSP; cancellation applies to provider operations.

Contract reference: AOSP frameworks/base, android-15.0.0_r1,
core/java/android/content/{ContentResolver,ContentProvider}.java.
No copied implementation beyond trivial API delegation. This patch does not
implement remote-provider Binder cancellation, MIME conversion, resource URI
opens or partial asset descriptors. Those pre-existing limitations are not
reported as supported. The Android reference provider also implements the
asset/typed-asset entrypoints used by Android's resolver.
