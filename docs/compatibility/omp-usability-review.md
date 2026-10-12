# OMP usability integration (2026-10-12)

Reviewed delivery: `feat/three-app-usability`, `3dec99b3..dde3cfdc`.
Integration starts from `f782a9fb`, preserving the newer text and image fixes.

## A: independent compatibility surface

AccountManager service lookup; WebView URL tracking and null-safe native URI;
PlaybackParams and ParcelUuid value/parcel contracts; StrictMode listener
configuration; disabled tracing/backup APIs; display capability queries;
caption defaults; listener registration; layout parameter copying; notification
channel state; foreground-service entry point; directBootAware parsing and
process package list. WindowInsets getters use the existing zero-inset model.
MediaController remains a documented surface without a media-session backend.

These are partial platform facilities: no backup service, StrictMode violation
detection, HDR output, layout transition engine, desktop caption preference
integration, foreground-service deadline enforcement, or media transport backend
is added. Notification Icon factories/rendering remain pre-existing stubs.

Upstream excerpts retain Apache-2.0 notices. OMP referenced AOSP frameworks/base
android-14.0.0_r1 for CaptioningManager, Display, WindowInsets and related public
API contracts. PlaybackParams/Violation cite framework revision
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d in source; ParcelUuid follows the SDK
source retained by the original delivery. New tests cover UUID/playback parcel
round trips, unset values, channel state, listener configuration and lookup.

## B: requires repairs before integration

- Bitmap: negative strides, integer overflow and native pixel conversion.
- NDK: incorrect return signatures for data-space/image-listener APIs; network
  error convention; passing ATL-private buffers to a host EGL extension.
- Native-library permissions: recursive chmod follows symlinks.
- Input: controller ownership, indiscriminate focusability and editor actions.
- Service executor overload ignores its executor; service resolution must not
  fall back to the caller for an explicitly missing package.
- MediaRouter: callback list mutation and unsupported presentation display.
- Canvas layers/gradients: validate state and native bounds, document limits.
- Heap and resource-only package contexts: targeted integration tests needed.
- Binder: delivery ends with the original false-returning stub. Reverts are not
  a useful patch; correct local dispatch must not be rejected merely because it
  exposes an application's failed service response.

## Held: signing identity replacement

980cc04a replaces the real APK signer with Google's identity for hosted GMS
packages. Package-name-based dispatch is insufficient authorization to grant
another signing identity. Do not integrate without an explicit, verified trust
policy. This may remove OMP's YouTube signature-check bypass; it does not make
stock or patched YouTube a working application. Existing main's legacy GMS
signature behavior is unchanged by A.

### A validation

Alpine build and complete mobile-smoke passed (including the new value tests).
Fresh-profile microG reached Google Accounts, Add account and the Google sign-in
WebView; relaunch repeated the flow and `atl-test` was visibly entered in the
Email or phone field without submitting. Both runs closed normally. Current
main failed the comparable flow with missing WebView.getUrl and JNI abort.
LocalSend rendered Receive, Send and Settings, returned to Receive and closed
normally after restart. The initial replay used incorrect bottom-tab coordinates;
only the subsequent side-panel replay establishes interaction success.
The remaining requested apps are checked with combined B, not claimed here.
