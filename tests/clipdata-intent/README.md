# In-process ClipData intent contract

Build inside Alpine: `sh tests/clipdata-intent/build.sh build-mobile OUT`.
Run OUT/probe.apk in a private ATL profile; sign/install the same APK on Android.
Expected Logcat tag ClipDataIntent: `CLIPDATA_INTENT_PASS checks=15`.

Checks: intent identity, item count/order, label, MIME type, null payloads,
plain-text and URI regressions, null item rejection and index bounds.
Verified: identical APK, Android emulator and ATL x86_64, 15 checks each.

Reference: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/content/ClipData.java and ClipDescription.java. Implementation
is limited to the in-process data model and newIntent factory. It does not
implement Parcelable, URI permissions, native non-text clipboard transport,
ContentResolver MIME discovery or MIME merging in addItem(ContentResolver,...).
Those existing gaps must not be confused with full Android clipboard support.
