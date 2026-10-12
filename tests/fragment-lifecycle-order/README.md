# Upward activity / fragment callback order

Build inside `atl_dev`:

```
sh tests/fragment-lifecycle-order/build.sh build-revanced /tmp/fragment-order
```

Run `/tmp/fragment-order/fragment-order.apk` through `scripts/mobile/run.sh`
with `ATL_BUILD_DIR` set to the tested build and a private `ANDROID_APP_DATA_DIR`.
Expect `FRAGMENT_ORDER_PASS` and exit 0. The same source can be compiled against
an Android SDK for reference testing; this change was checked against AOSP
Activity.performStart/performResume in android-17.0.0_r1.

The probe verifies the exact sequence for start and resume: application
callbacks remain inside the activity's super call; fragment callbacks run after
the whole activity override. The old runtime fails with "Fragment started
inside Activity.onStart". Both callbacks occur once. Fragment dispatch is not
reached if the activity override throws.

This is deliberately not a complete FragmentManager implementation. Fragment
creation/catch-up, activity-local API-29 lifecycle callbacks, saved-state
restoration and view/back-stack management need separate work. A private
ReVanced run no longer hits the original tracing scope exception; it exposes
another initialization failure because fragment onActivityCreated is missing.
