# Small compatibility contracts

ATL-specific tests for its current storage mapping and absent Bluetooth backend,
plus cancellation of the legacy single-file media provider. No user app data is
used. `ATL_MEDIA_ROOT` must be unset to exercise the fallback provider.

Build inside Alpine:

    ANDROID_JAR=/path/to/Android/Sdk/platforms/android-37.0/android.jar \
      sh tests/storage-bluetooth-media/build.sh build-mobile /tmp/smallcompat-build

After sourcing `scripts/mobile/environment.sh` with the correct runtime prefix:

    GDK_BACKEND=x11 GTK_USE_PORTAL=0 TEST_NATIVE_CANCEL=1 \
      dbus-run-session -- xvfb-run -a \
      sh tests/storage-bluetooth-media/run.sh build-mobile \
      /tmp/smallcompat-build/probe.apk /tmp/smallcompat-results

Use a private D-Bus session: Xvfb alone does not isolate host portal dialogs.
The native cancellation check needs xdotool. Require both SMALL_COMPAT PASS
and NATIVE_CANCEL PASS, and a zero exit code. The runner imposes bounded waits.

Contracts:
- ATL's app-data-backed external root and descendants are emulated, non-removable.
- Unknown paths, including prefix siblings and parent traversal, are rejected;
  null is rejected. Nonexistent descendants of the known root are accepted.
- Both Bluetooth lookup APIs report no adapter; isEnabled exists for linkage.
- Cancelled media selection returns no rows/type, and openFile throws instead
  of dereferencing null. Selecting a test file restores usable metadata/opening.
- Cancelling a real GTK chooser releases the waiting query and returns no rows.

Storage contract reference: AOSP android-17.0.0_r1,
frameworks/base/core/java/android/os/Environment.java,
isExternalStorageEmulated(File) and isExternalStorageRemovable(File).
Implementation is ATL-specific, not copied AOSP volume-management code.

Validation (2026-10-15): combined probe, private-bus native cancellation, and
existing mobile-smoke all passed against rebuilt build-mobile. Organic Maps
and Rhythm survived their previous missing-method blockers during 45-second
isolated startup runs; this is not full interactive app validation. Rhythm
still reports AndroidCAStore and media-session errors. Organic Maps reports a
certificate parsing error. Neither app's complete functionality is claimed.

AntennaPod's CollapsibleSearchView is defined in classes2.dex of the tested APK;
its recorded ClassNotFoundException still needs a reproduction with classloader
inspection. No speculative classloader changes are included here.

Additional regressions (2026-10-15): all four public SurfaceView constructors
must initialize a stable holder and surface. The four-argument constructor
failed before the fix. View Z is elevation + translationZ; changing Z must
reorder attached widgets without detaching them. FinishLaunch finishes the
last activity and immediately requests Survivor: FINISH_LAUNCH PASS must appear
before normal exit. Before the deferred-close fix the process exited early
with status 0, so exit status alone is not an adequate assertion.
Organic Maps now renders the downloaded world map and responds to panning;
location permissions and visual styling remain incomplete.
