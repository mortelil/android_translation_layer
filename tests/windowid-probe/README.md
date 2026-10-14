# Window identity regression

Build in the ATL container:

    sh tests/windowid-probe/build.sh BUILD_DIR OUTPUT_DIR

Sign windowid-probe.apk with a test key and run in an empty profile. The same
APK can run on an Android emulator. Success requires RESULT failures=0 and
files/results.json with 24 checks and zero failures, not just process exit.

Checks cover unattached views, sibling identity, two different windows,
reparenting, repeat calls, hash codes, null and unrelated objects. The 24
assertions passed against the integration build and an Android emulator during
review. The result JSON was added afterwards for the ATL probe runner.

Scope: local window identity only. Parcelable transport and WindowId focus
observers remain unimplemented. This does not claim a complete WindowId API.

AOSP comparison source: frameworks/base, commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/view/WindowId.java.
