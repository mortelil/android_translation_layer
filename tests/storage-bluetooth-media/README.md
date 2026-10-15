# Small compatibility contracts

ATL-specific tests for its current storage mapping and absent Bluetooth backend,
plus cancellation and persistence of the directory-based media provider. No user app data is
used. `ATL_MEDIA_ROOT` must be unset to exercise the directory chooser.

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
  of dereferencing null. Selecting a directory exposes indexed media files.
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


Directory/audio milestone:
- Native folder selection persists a canonical directory per app. An explicit
  ATL_MEDIA_ROOT overrides it. Cancelling does not choose a default directory or
  repeatedly reopen the dialog during the same process.
- Audio headers/tags are read with libavformat, without playback. Supported
  extensions: mp3, flac, wav, ogg, opus, m4a, aac. Network protocols/playlists
  are excluded. Unknown tags/durations remain unknown; display title falls back
  to the filename. Audio tracks expose stable IDs and artist/album grouping IDs.
- Existing image indexing, .nomedia/hidden/symlink filtering and read-only file
  access remain in place. Audio cannot be opened using an images URI.
- Global and per-track genre queries expose scanned genre tags. The per-track
  URI follows AOSP frameworks/base commit af74e66,
  core/java/android/provider/MediaStore.java (getContentUriForAudioId).
- Separate albums/artists/playlists collection APIs are not implemented;
  requesting them fails explicitly rather than returning track rows as albums.
- No live filesystem observer or complete MediaScanner/MediaStore write API.
  Queries on the UI thread return empty while the chooser is pending to avoid
  deadlock; such clients may require refresh/restart after choosing a directory.
- The native cancellation test and run-folder.sh use a private D-Bus session and
  Xvfb. run-folder.sh BUILD APK FIXTURES OUTPUT selects the synthetic fixture
  directory, then starts a second process and requires FOLDER_PROBE PASS in
  both runs. Generate fixtures with tests/mobile-smoke/fixtures.py first.
- mobile-smoke tests real WAV tags/duration, corrupt audio rejection, hidden and
  symlink filtering, collection isolation, and reading bytes through content URI.
  Actual Rhythm playback is not established: its media-session rejection is a
  separate existing failure, and its GUI also has font/icon/layout defects.
  With directory lookup and genre lookup working, Rhythm next terminates on
  missing android.media.MediaExtractor in AudioFormatDetector. This milestone
  does not implement extraction/playback or claim a working Rhythm library UI.

Notification people regression (2026-10-16): NotificationPeople calls the
legacy addPerson(String) entry point directly, then checks ordered metadata,
duplicates/nulls, array isolation and an empty independent builder. No real
contacts are used and no notification is posted. The unpatched runtime fails
with NoSuchMethodError; require NOTIFICATION_PEOPLE PASS after rebuilding.
Contract reference: AOSP frameworks/base android-6.0.1_r1,
core/java/android/app/Notification.java, Builder.addPerson and buildExtras
(EXTRA_PEOPLE). This is an ATL implementation of the legacy metadata contract,
not full modern Person, notification ranking, or desktop presentation support.
Existing Builder.build object reuse and extras setter stubs are unchanged.
