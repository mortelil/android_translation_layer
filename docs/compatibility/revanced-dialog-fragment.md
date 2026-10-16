# ReVanced: dialog fragments and window media-controller association

Reference: AOSP frameworks/base android-17.0.0_r1, commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/app/DialogFragment.java
and Activity.java. The adapted code retains Apache-2.0 attribution.

ATL now supports dialog-producing platform fragments: queued show, onCreateDialog,
show on start, hide on stop, cancel/dismiss callbacks, removal on dismissal and
cleanup on destruction. A dismissal before a transaction is executed is retained.
Dialog.hide no longer destroys the native window. Dialog.onCreate runs once;
repeated cancel/dismiss does not dispatch duplicate callbacks. Native user close
honors cancelable, while explicit programmatic cancel remains available.

Activity.setMediaController/getMediaController delegate to the Window association.
This does not implement media-session transport, volume/media-key routing or
playback; the pre-existing MediaController backend remains incomplete.

## Tests

Build tests/mobile-smoke/build.sh and launch mobile-smoke.apk with
`-l DialogFragmentActivity` in the configured Alpine runtime and a private profile.
Expected: DIALOG_FRAGMENT_PASS, exit 0. The probe opens a real AlertDialog,
checks hide/show without recreation, noncancelable user cancellation, duplicate
cancel, dismissal removal, dismissal before pending add, and controller
association/clearing/window isolation. Also run fragment-lifecycle-order and the
ordinary full mobile-smoke suite.

## Limits

This is not the complete platform fragment implementation. No embedded fragment
view inflation, back stack or process-death saved-state reconstruction is added.
`dismissAllowingStateLoss` currently uses the same queue as ordinary dismissal:
ATL has no fragment saved-state barrier. Existing window feature/theme and
no-input flag support is incomplete; declaring a style is not proof of its
native enforcement. Dialog still uses the pre-existing deferred dismissal path.

## App evidence, 2026-10-16

ReVanced YouTube 20.40.45, package app.revanced.android.youtube, in a private
profile with registered app.revanced.android.gms:

1. Missing DialogFragment at Utils.setContext is resolved.
2. Subsequent missing Activity.setMediaController is resolved.
3. MainActivity creation/start is reached, but YouTube reports
   `Failed to fetch kids onboarding status, finishing the App.`
4. microG separately reports `Service not supported: 161`; this log alone does
   not prove that service is the cause of the kids-onboarding failure.
5. During finish, MainActivity.onPause throws `Required value was null` in
   aqhy.r / aqhw.close. This remains unresolved.

The later failure reproduces with both the locally signed GmsCore APK and the
previously verified official ReVanced GmsCore APK (SHA256
62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65).
No account state copied, no login/playback success claimed, and no app checks,
signatures or TLS validation bypassed.
