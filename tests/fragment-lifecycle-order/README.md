# Activity / fragment callback order

Build inside `atl_dev`:

```
sh tests/fragment-lifecycle-order/build.sh build-revanced /tmp/fragment-order
```

Run `/tmp/fragment-order/fragment-order.apk` through `scripts/mobile/run.sh`
with `ATL_BUILD_DIR` set to the tested build and a private `ANDROID_APP_DATA_DIR`.
Expect `FRAGMENT_ORDER_PASS`, `FRAGMENT_DOWNWARD_ORDER_PASS`, and exit 0. The same source can be compiled against
an Android SDK for reference testing; this change was checked against AOSP
Activity.performStart/performResume in android-17.0.0_r1.

The probe verifies the exact sequence for start and resume: application
callbacks remain inside the activity's super call; fragment callbacks run after
the whole activity override. The old runtime fails with "Fragment started
inside Activity.onStart". Both callbacks occur once. Fragment dispatch is not
reached if the activity override throws.

This is deliberately not a complete FragmentManager implementation. Fragment
creation/catch-up, activity-local API-29 lifecycle callbacks, saved-state
restoration and view/back-stack management need separate work. ReVanced integration evidence and remaining limitations are recorded in
`docs/compatibility/revanced-dialog-fragment.md`.

Downward transitions are also checked through a real `finish()` request:
fragment pause/stop/destroy precede the whole activity override, and Application
callbacks remain inside the super call. This follows AOSP Activity.performPause,
performStop and performDestroy in android-17.0.0_r1 (frameworks/base commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, lines 9554–9675).
Dispatching fragments inside super.onPause can close an AndroidX lifecycle
observer's tracing scope while the app's overriding onPause is still running.
The test checks each event exactly once, including complete teardown.
