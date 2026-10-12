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

## B: reviewed repairs

- Bitmap: negative strides, integer overflow and native pixel conversion.
- NDK: incorrect return signatures for data-space/image-listener APIs; network
  error convention; passing ATL-private buffers to a host EGL extension.
- Native-library permissions: recursive chmod follows symlinks.
- Input: balance controller references, create a key controller when input starts,
  and preserve the existing focusability rules for other views. No app-specific
  Return/validation override.
- Service executor overload ignores its executor; service resolution must not
  fall back to the caller for an explicitly missing package.
- MediaRouter: callback list mutation and unsupported presentation display.
- Canvas layers/gradients: validate state and native bounds, document limits.
- Heap and resource-only package contexts: report the configured 256/512 MiB
  ART profile and real host memory; preserve restricted/no-code flags.
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

### B implementation and scope

Pixel reads/writes now respect subrectangles and signed row strides, check Java
index arithmetic in 64 bits, convert RGBA bytes explicitly, and replace alpha
instead of compositing new pixels over old ones. Layer alpha and linear gradients
have pixel-level smoke assertions. Shader local matrices, MIRROR tiling (currently
warns and clamps), and arbitrary saveLayer paint effects remain incomplete.
Pre-P target SDKs retain their legacy save-flag behavior.

Local Binder dispatch invokes onTransact, rewinds input/reply parcels, preserves
false for unknown codes and propagates failures. This does not add remote Binder
IPC. Service lookup handles explicit packages without falling back to the caller;
action/priority matching remains partial (not a full Android intent resolver).
The Executor binding overload dispatches asynchronously and maps unbind back to
the caller's original ServiceConnection. Resource-only package contexts do not
load foreign dex. INCLUDE_CODE still uses ATL's heavier application loader.
CONTEXT_RESTRICTED is reported but does not introduce Android's full sandbox.
MemoryInfo describes host /proc memory, not container/cgroup limits.

Native-library permissions use openat/O_NOFOLLOW and fchmod on opened files,
including directory traversal. Symlinks and special files are not chmod'd.
NDK data-space and image-listener declarations now have their actual return
values and explicitly fail unsupported operations; network binding returns -1
and errno; an ATL-private hardware buffer is never passed to the host's Android
EGL extension. CPU lockPlanes preserves the base data pointer. MediaRouter
supports local/default and application-created routes, not device discovery or
casting; callback mutation is safe and no presentation display is fabricated.

Contract references for the repairs: AOSP android-14.0.0_r1,
frameworks/base/core/java/android/os/Binder.java and android/graphics/Canvas.java;
frameworks/native/libs/nativewindow/include/android/native_window.h;
frameworks/av/media/ndk/include/media/NdkImageReader.h; and the installed AOSP
android/multinetwork.h. Original OMP AOSP source/license notes are retained.

### Held: native Choreographer

OMP's global GLib idle callbacks are neither per-Looper callbacks nor vsync
scheduling. Returning NULL from an exported replacement also stalled the tested
Flutter startup. The complete new native Choreographer export is therefore held;
main's Java fallback remains available. Apps requiring those native symbols can
still fail to link. This explicitly loses OMP's native-symbol workaround and
needs a real Looper/frame scheduling implementation, not a success stub.

### Test evidence

Private artifacts and logs are under
`/home/morten/src/omp-integration-tests/`; no APKs, appdata, private screenshots or
large logs are committed. Source OMP checkout and all original appdata are
unchanged. Tests use a dedicated build-mobile in the integration checkout;
artifact SHA256s and loaded /proc mappings record the selected JAR/library paths.

- Full mobile-smoke with new pixel, layer/gradient, memory, package and Binder
  assertions; separate synthetic Executor activity (off-main callback and unbind).
- Native failure-return contracts; symlink-target permission preservation;
  existing hardware-buffer, shared-memory and AAudio null-device tests.
- App replays and final outcomes are recorded below. A helper exit code alone is
  not an interaction pass. No real account login or media upload was performed.

### App matrix (x86_64, Alpine atl_dev, private Xvfb/test profiles)

| App | Checked functionality | Result versus baseline / OMP |
| --- | --- | --- |
| Messenger 558.0.0.21.77 | Login screen, email and masked password input, close and restart | Responsive; baseline functionality retained. No login submitted. |
| Immich 3.3.0 | Server screen, dummy server text, close and restart | Responsive, as on main; no server connection/photos tested. Settings click did not establish navigation, so that is not counted as passed. |
| LocalSend 1.18.2 | Receive, Send, Settings, close and restart | Responsive after completing the first-run What's new screen. The earlier replay that only clicked behind that screen is not a pass. No transfer claimed. |
| microG 252432034 | Accounts → Add → Google sign-in, Email or phone input, close/restart | Responsive on B with dummy text; main failed at WebView.getUrl. No login submitted. |
| ReVanced Manager 2.6.0 | Main, Settings/back, search, local APK picker, close/restart | Main no longer has OMP's reported black boxes. Some icons still draw incorrectly after navigation. |
| ReVanced patch pipeline | YouTube 20.26.46 fixture, 61 selected patches, resource/dex compilation and signing | Repeated because native/bitmap/heap repairs affect this path. Worker succeeded; output ZIP CRCs, manifest/resources and eight dex files checked; Android apksigner verifies v2/v3 signatures. Not installed/logged in. |
| YouTube stock 21.41.525 universal | Existing startup scenario | Main exits 10 at missing StrictMode.penaltyListener. B passes that and exits 10 at libgoogle3.so/EntityStore loading: missing AChoreographer_getInstance. No useful window. OMP's white-window state is not retained by the held native scheduling workaround. |
| YouTube patched 20.26.46 | Existing startup scenario | Both main and B report missing ReVanced GmsCore and show no app UI. Exit 0 is not an app pass. |

ReVanced output SHA256:
`120079ae4d3ff4191760bf3a0766fad7956e1978ad22910ec77de9e80de003af`.
The pipeline used the repaired bitmap/native/heap code. The subsequent changes
only restored an existing Bitmap JNI export, narrowed key-controller creation and
retained pre-P save flags; targeted smoke and UI checks cover those changes.

Reproduction uses `tests/mobile-smoke/build.sh` with JDK 8 followed by
`tests/mobile-smoke/run.sh` under the established test environment and Xvfb.
Launch `org.atl.mobile.smoke.ExecutorProbeActivity` from the same generated APK
for the Executor test. Run `sh tests/native-compat/run.sh` and
`sh tests/ndk-compat/run.sh /absolute/path/to/output-binary` inside atl_dev.
The latter links the tested production units with section GC, not substitute
functions; no Java VM or real audio device is needed.

### ReVanced option input

The v2.6.0 `OptionFields.kt` uses a multiline OutlinedTextField; its onValueChange
stores valid text immediately. Return adds a newline, not a save action. After
clearing with Backspace/Delete, entering exactly `com.google`, going back and
reopening the options dialog, the value remained exactly `com.google` without an
Invalid value error (`final-options-exact`). No validation bypass was added.
Ctrl+A/End did not reliably select/move in this Compose field during earlier
replays, leaving old characters behind. That keyboard-shortcut issue remains;
the repeated-delete replay distinguishes it from option persistence.
