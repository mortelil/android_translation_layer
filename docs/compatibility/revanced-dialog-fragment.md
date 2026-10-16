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

## Initial app evidence, before follow-ups (2026-10-16)

ReVanced YouTube 20.40.45, package app.revanced.android.youtube, in a private
profile with registered app.revanced.android.gms:

1. Missing DialogFragment at Utils.setContext is resolved.
2. Subsequent missing Activity.setMediaController is resolved.
3. MainActivity creation/start is reached, but YouTube reports
   `Failed to fetch kids onboarding status, finishing the App.`
4. microG separately reports `Service not supported: 161`; this log alone does
   not prove that service is the cause of the kids-onboarding failure.
5. During finish, MainActivity.onPause threw `Required value was null` in
   aqhy.r / aqhw.close. The follow-up below fixes its lifecycle ordering.

The later failure reproduces with both the locally signed GmsCore APK and the
previously verified official ReVanced GmsCore APK (SHA256
62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65).
No account state copied, no login/playback success claimed, and no app checks,
signatures or TLS validation bypassed.

## Follow-up: shutdown and accounts (2026-10-16)

The shutdown tracing exception was caused by dispatching platform Fragment
pause/stop/destroy inside the application's override. AOSP dispatches those
fragment transitions before entering the override (Activity.java 9554–9675,
android-17.0.0_r1, frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d).
The extended fragment-order probe checks the full real finish sequence.
CaptioningManager now releases registered listeners; its preferences remain
fixed and it does not manufacture change notifications.

ContentProviderClient.call now forwards local app-defined operations, including
nullable arguments/results and provider exceptions. This is the local contract
from AOSP ContentProviderClient.java 580–592 and ContentProvider.java 2754–2762
at the same revision. That first patch did not provide cross-process transport.
The mobile-smoke provider verifies dispatch, state, null default and errors.

At that checkpoint, ReVanced 20.40.45 with official ReVanced GmsCore 0.3.13.2.250932 exited on
"Failed to fetch kids onboarding status". The account-free Android 17 emulator
with the same APKs reaches YouTube's cookie consent page instead. No login was
performed. Inspection of the APK traces the account request to
`acquireContentProviderClient("app.revanced.android.gms.auth.accounts")`, then
`call("get_accounts", "app.revanced", extras)`. At that checkpoint ATL only resolved providers in
the current process. The installed GmsCore manifest exports this provider in
its default process. An exception fetching accounts activates the onboarding
check; another fetch failure becomes an empty list and the app finishes.
The unsupported service 161 warning is not established as the cause.

## Cross-package provider milestone

Installed authorities now resolve to a provider hosted in its owning APK and
manifest process. ContentProviderClient.call and bounded query snapshots travel
through the existing private Binder channel. Caller identity, owner context,
authority aliases, exceptions, client release and process death are covered by
two synthetic APKs in tests/provider-call. Intent parcels preserve supported
fields and nested extras so provider-returned intents can cross the channel.

This is deliberately narrower than a system-wide Android process manager.
Hosts are shared within one caller, not with independently running applications
or service-controller hosts. Permission-protected cross-package providers are
refused until permission enforcement is implemented. Stable-provider death
semantics, live cursors, file operations, mutations and URI grants are not
implemented. See tests/provider-call/README.md for limits and reproduction.
No account response is fabricated by the bridge.

Verification on x86_64 in atl_dev: two-APK provider suite (including typed query,
oversize rejection, nested parcelables, process death and reacquisition), existing
service-process P01/P02, fragment lifecycle/dialog probes and full mobile-smoke
passed. ARM/Nura was not tested in this milestone.

ReVanced now passes the earlier kids-onboarding account-provider failure and
reaches WebView creation. Subsequent missing account-view, context-clickable,
Wi-Fi SSID and WebView class-loader accessors were implemented with smoke tests.
AccountManager still has no account persistence/visibility backend: its empty
store stays empty, and a nonempty store is explicitly rejected by the new
package-scoped accessor until visibility policy exists. Wi-Fi SSID reports the
Android unavailable value. WebView exposes its actual class loader, not a
Chromium compatibility implementation. Login and playback are not verified.

Final bounded app run after the WebView accessor still exited (status 10). It
reported missing WifiInfo.getLinkSpeed() and
ActivityManager.RunningAppProcessInfo.importanceReasonComponent, plus a
VideoFrameReleaseHelper thread-without-Looper warning. These are remaining
blockers, not a successful app launch. No additional fixes were attempted in
this milestone. The synthetic transport and full mobile-smoke tests passed;
the UI harness completing is not counted as ReVanced passing.
