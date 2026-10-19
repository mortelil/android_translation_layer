# Intent URI parsing contracts

Run inside Alpine with ATL_PREFIX, ATL_CORE_JAR and ATL_BUILD_DIR explicitly
pointing at the candidate runtime, then:

    sh tests/intent-uri/run.sh /tmp/intent-uri-probe

The runner prints the candidate hax.jar/api-impl.jar hashes. Set
ATL_TEST_RUNTIME_JAR to a baseline api-impl.jar to verify the probe detects the
previous missing API (NoSuchMethodError at parseUri).

The same generated test.jar can run against Android's own framework:

    adb push /tmp/intent-uri-probe/test.jar /data/local/tmp/atl-intent-uri-test.jar
    adb shell 'CLASSPATH=/data/local/tmp/atl-intent-uri-test.jar app_process /system/bin TestIntentUri'

Require both INTENT_URI_PASS and a successful exit. On Android, uncaught
exceptions may only appear in AndroidRuntime logcat; a killed process is a
failure, never a pass. No app/account data is used. Runtime.halt is used after
all assertions to avoid the existing standalone ART shutdown issue.

80 assertions cover plain web URLs, literal/encoded fragments and plus signs,
modern intent: and android-app: links, old intent fragments, all supported
primitive extras, categories, MIME, component expansion, unsigned flags,
default stripping of URI-grant flags and explicit URI_ALLOW_UNSAFE, selector
handling, malformed input, and field preservation through copying/Parcel.
Bounds, extras and selectors are checked for aliasing after copies.
The public CREATOR field has the Android Parcelable.Creator descriptor even
though ATL uses a ClassLoaderCreator implementation internally.

## Source and scope

URI parsing is adapted from AOSP frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d (android-17.0.0_r1),
core/java/android/content/Intent.java:
parseUri, parseUriInternal, getIntentOld, decodeInteger, associated URI/flag
constants. Apache-2.0 attribution is retained in the implementation.
Original OMP port: c266ec8519e6bb0d1c349e202f7b49b2b8f009bf.

ATL's Bundle is eagerly allocated, so the AOSP lazy allocation is omitted;
field names and setters are adapted. ATL does not model Android's local
FROM_URI marker (framework-only trust bookkeeping). This patch does not grant
URI permissions, implement routing policy, implement toUri serialization, or
replace IntentFilter/package resolution. Parsing is not authorization.
ATL's custom Parcel format is not Android's wire format; endpoints must use
the same runtime build. The appended identifier/extended flags/bounds fields
are not compatible with old ATL Intent parcels.
