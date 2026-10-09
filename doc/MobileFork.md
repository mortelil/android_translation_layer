# Experimental Linux mobile fork

This fork is for running applications on Linux phones, initially Immich. It is
unofficial and is not maintained or endorsed by ATL or Immich. Changes were
developed with OpenAI Codex assistance and debugging on real devices. This is an
experimental compatibility branch, not complete Android support. It does not
modify or redistribute the Immich APK.

## Source projects

Keep these repositories beside one another:

```sh
mkdir atl-mobile-workspace
cd atl-mobile-workspace
git clone -b linux-mobile-experimental https://github.com/mortelil/android_translation_layer.git
git clone -b linux-mobile-experimental https://github.com/mortelil/bionic_translation.git
git clone -b linux-mobile-experimental https://github.com/mortelil/art_standalone.git
```

```
workspace/
  android_translation_layer/
  bionic_translation/
  art_standalone/
```

Use the three matching experimental forks, not unmodified dependencies from
upstream. `dependency-lock.json` records the exact dependency commits. Upstream
history, license headers and attribution are retained in all three repositories.
Their original projects are:

- https://gitlab.com/android_translation_layer/android_translation_layer
- https://gitlab.com/android_translation_layer/bionic_translation
- https://gitlab.com/android_translation_layer/art_standalone

## Build on Alpine edge

The scripts target Alpine edge x86_64 and native aarch64 builds. They are not a
cross-compilation toolchain. On Mint use an Alpine Toolbox container; do not run
the resulting Alpine executables directly on the host. The home directory can
be shared with Toolbox. Install development packages inside Alpine:

```sh
sudo apk add git build-base meson python3 pkgconf java-common openjdk8-jdk \
  android-build-tools elfutils-dev libunwind-dev libbsd-dev libcap-dev \
  pc:alsa pc:glib-2.0 pc:gtk4 pc:fontconfig pc:pangoft2 pc:pangocairo pc:gudev-1.0 pc:libportal pc:openxr \
  pc:vulkan pc:webkitgtk-6.0 pc:libsoup-3.0 pc:libsecret-1 ffmpeg-dev \
  bionic_translation-dev art_standalone-dev libandroidfw-dev
```

Alpine's edge/testing repository must be enabled for the ATL dependencies; see
the upstream [build guide](Build.md). A PulseAudio-compatible output can require
Alpine's `alsa-plugins-pulse` package. The scripts do not install system packages.

Inside the ATL checkout, inside Alpine:

```sh
JOBS=4 scripts/mobile/build.sh
sudo apk add font-dejavu py3-fonttools # font test fixtures
GDK_DISABLE=glx scripts/mobile/test.sh
```

The build compiles bionic_translation, ART's modified libutils, the patched WolfSSL
Java provider, and ATL. The remaining runtime is supplied by Alpine packages.
It installs a private overlay in `../.atl-runtime` and builds ATL in `build-mobile`.
It does not run `sudo meson install` or replace system boot jars. The launcher
selects the rebuilt provider using BOOTCLASSPATH and puts the local native
libraries first in LD_LIBRARY_PATH. The native libutils ABI must match its ATL
consumer; update/build these repositories together.

The build checks the companion revisions against the lock file. For intentional
development with modified dependencies, set `ATL_ALLOW_DEPENDENCY_CHANGES=1` and
record the revisions you actually tested.

`ATL_WORKSPACE`, `ATL_PREFIX` and `ATL_BUILD_DIR` can override those paths. Choose
paths without spaces: the inherited ART build system and javac source-list
handling have not been validated with spaces. `JOBS=2` is suitable for a slower
phone. A working graphical session is needed for the APK regression tests.

## Run an APK

Use an APK matching the machine's architecture, downloaded separately. On a Mint
host with a container called `atl_dev`:

```sh
toolbox run --container atl_dev sh -lc \
  'GDK_DISABLE=glx /absolute/path/to/android_translation_layer/scripts/mobile/run.sh /absolute/path/to/app.apk'
```

On an Alpine phone, run from its graphical session:

```sh
ATL_DISABLE_FULLSCREEN=1 scripts/mobile/run.sh /absolute/path/to/app-arm64.apk
```

Leave `ATL_RENDER_SCALE` unset initially. The desktop compositor may already
scale the phone's logical display to its physical resolution; setting this to
that same compositor factor scales the app a second time and can make controls
too large or clip them. Set it only when you have confirmed that the app needs a
higher-resolution backing surface, and tune it independently of compositor
scaling. `ATL_RENDER_SCALE=1` is equivalent to the default rendering size.
The launcher inherits Wayland/X11, D-Bus and audio session settings. It does not
guess display names or a remote user's runtime directory.

By default, app data goes in `$XDG_DATA_HOME/atl-mobile-experimental`, or
`~/.local/share/atl-mobile-experimental`. Set `ANDROID_APP_DATA_DIR` explicitly to
reuse existing data. The supplied launcher does not automatically open an old
Immich account or change the application's backup setting.

To expose a directory to the image MediaStore provider, explicitly set:

```sh
ATL_MEDIA_ROOT="$HOME/Pictures" scripts/mobile/run.sh /absolute/path/to/app.apk
```

The provider offers read-only file access, but an app can upload readable photos
if backup is enabled. The media-root setting is not a sandbox for the rest of the
process. No personal directories are exposed by default by these scripts.

## Scope and limits

- Flutter startup/input: practical compatibility additions, including GLib/Looper
  scheduling changes. ATL already had event-loop integration; this branch extends
  it and changes scheduling. The necessity of every change is not established.
- Text: editing, composition and Pango layout are partial Android implementations;
  complex input methods, accessibility and selection feedback need more work.
- Graphics: real RGBA SurfaceTexture/ImageReader paths, Matrix copy, affine
  transforms, buffer resize and view invalidation. Not a zero-copy pipeline or
  a complete implementation of all image formats/TextureView/Canvas operations.
- Video: observed progressing frames and working pause/back on the test phone.
  Performance, audio/video synchronization and codecs are not comprehensively tested.
- MediaStore: opt-in JPEG/PNG/WebP/GIF image indexing and thumbnails; no local video,
  HEIC/RAW indexing, write/delete API, full EXIF metadata or filesystem watcher.
- Network: simplified availability callbacks; request filtering/network identities
  need further work. Cleartext rules are only part of network-security policy.
- Display: manual scale; full Android insets and automatic monitor-scale changes
  are not implemented. Disabling fullscreen is a desktop integration choice.
- Autofill, spellchecker and tracing use unavailable/disabled service behavior;
  they are not host service integrations.

See [MobileValidation.md](MobileValidation.md) for exactly what was tested, and
[MobileProvenance.md](MobileProvenance.md) for source and license records.

## Contributions

Keep experimental changes separate from proposals to ATL upstream. Prefer small
reproducible bugs with synthetic tests, retain notices on borrowed code, and name
the exact repository/path/commit for new imports. Disclose AI assistance. A
successful app launch does not establish full API correctness or security.

### Experimental Java core desugaring (Plexamp)

Plexamp's React Native/Expo startup exercises Java streams. The packaged core
libraries currently contain lambda call sites unsupported by this ART build.
An optional private core JAR can be built from the installed matching class files
with Google's D8 compiler. This also preserves the original ICU resources.

Download the pinned tool from
<https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar>, then inside Alpine:

```sh
R8_JAR=/absolute/path/r8-8.3.37.jar sh scripts/mobile/build-core-java.sh
ATL_CORE_JAR=/absolute/path/to/.atl-runtime/share/art/core-all-hostdex.jar \
  sh tests/core-java/run.sh
ATL_CORE_JAR=/absolute/path/to/.atl-runtime/share/art/core-all-hostdex.jar \
  sh scripts/mobile/run.sh /absolute/path/to/Plexamp.apk
```

The builder checks the compiler checksum and requires Java 21 (override
`D8_JAVA` for its executable), Python 3 and `art_standalone-dev`. Activation is
explicit through `ATL_CORE_JAR`; no system boot JAR is replaced. This remains
experimental and does not imply that Plexamp is fully supported.

The launcher fixes a writable `XDG_CACHE_HOME` before loading Android native
libraries. If no desktop cache was provided, it uses `.cache` within ATL's app
data directory. This prevents ART from following an Android library's later
change of `HOME` to an Android-only location.

Plexamp also exercises the previously empty HTTP `CookieManager`. Its new
libsoup-backed store is per app (`atl-cookies/cookies.sqlite` below the app data
directory), with serialized worker-thread access and callbacks posted to the
caller's Looper. Persistent cookies use SoupCookieJarDB's synchronous writes;
session cookies remain in memory. The regression APK checks host-only/domain,
path, Secure, expiry, deletion, accept policy and callback threading with
synthetic domains. This store is **not yet shared with ATL's experimental
WebKit WebView**; per-WebView third-party policy remains unimplemented.
Partitioned cookies and file-scheme cookies are not supported (partitioned
cookies are rejected instead of being stored without their isolation).
Reference contracts: [Android CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)
and [SoupCookieJarDB](https://libsoup.gnome.org/libsoup-3.0/class.CookieJarDB.html).

Optional NFC module reflection can resolve `Tag`, `Ndef` and `TagTechnology`.
These are unavailable-hardware type shells, **not an NFC implementation**:
no tags can be created, the default adapter remains null, and tag parcel input
is explicitly unsupported. No hardware features are advertised by this change.

GTK-backed custom view drawing now scopes each `View.draw` call with a Canvas
save/restore boundary. Clips and transformations are unwound even after draw
exceptions, and the transient GTK snapshot pointer is cleared. A synthetic
regression test covers repeated draws, nested clips, transforms and exceptions.
This prevents custom view drawing state from leaking across GTK snapshots.
A separate GTK snapshot imbalance remains during Plexamp startup (apparently
outside this view-drawing scope). Plexamp now displays its onboarding/sign-in UI;
login and audio playback are not verified by this experiment.

Detached views now retain the same floating `ViewTreeObserver` across repeated
lookups, preserving listeners until attachment. Previously each lookup replaced
the observer, which could lose pre-draw listeners used by React Native safe-area
initialization. The detached lookup also avoids a GLib critical on a null GTK
root. A regression test checks observer identity and listener delivery.

`NetworkInfo` now exposes the Android `DetailedState` constants and reports
CONNECTED/DISCONNECTED consistently with ATL's native availability snapshot.
The previous empty enum caused a hidden `NoSuchFieldError` in Expo's network
probe, preventing Plexamp from reaching text layout. This does not add Internet
validation or captive-portal detection.

`StaticLayout.Builder.setUseLineSpacingFromFallbacks(true)` uses Pango's existing
per-run font metrics. The `false` mode is explicitly unsupported; primary-font-only
line metrics still need implementation. The text test checks multilingual line
metrics and explicit rejection of that unsupported mode. Other existing builder
limitations remain.

The full-keyboard `QwertyKeyListener` handles Unicode key input, selection
replacement, backward/forward code-point deletion and ACTION_MULTIPLE text.
GTK/IME owns composition and modifier state. Autocorrect, Android dead-key spans,
latched modifiers and extended grapheme deletion are not implemented. The
previously empty KeyListener interface now exposes its editing callbacks with
unhandled defaults for existing incomplete listeners. This is not a complete
Android keyboard framework replacement.

TextView and EditText retain OpenType feature settings and apply them to their
GTK Pango attributes. View autofill hints/importance are stored as metadata;
there is still no autofill provider. Inverse path clipping now renders through
GSK inverse-alpha masks, including even-odd and inverse path fill modes, and
participates in save/restore. A bitmap pixel test verifies the removed interior
and restoration. Clip-emptiness/bounds queries remain conservative, as in the
existing Canvas implementation; this is not complete clipping-query support.


`EditText` now keeps a persistent Editable and delivers before/on/after text
notifications for native GTK edits and programmatic replacements. The GTK bridge
uses UTF-16 change ranges, round-trips supplementary Unicode, supports selection
and applies InputFilters to native edits. It no longer continues Java callbacks
with a pending exception. The three-argument constructor used by AndroidX also
initializes the default text input type. Password input variations select GTK's
masked text mode; `setRawInputType` retains metadata without changing transforms.
These are single-line GTK fields, not a complete Android editor/IME implementation.
Arbitrary transformation methods, styled editable text, selection handles and
full Android key-listener integration remain incomplete.

ForegroundColorSpan and AbsoluteSizeSpan now store their values and affect
Pango measurement/rendering through UTF-8 span ranges. DynamicLayout refreshes
these attributes after text/span changes. Only these two style attributes are
covered: typeface/custom-font spans (including Plexamp's icon font), other span
classes and the custom non-GSK Canvas replay path remain incomplete. The native
text bridge rejects invalid UTF-16 rather than passing modified UTF-8 to Pango.

JobInfo retains content-trigger URIs and update/max-delay metadata. The current
JobScheduler has no functioning content-observer backend: scheduling a job with
these triggers returns RESULT_FAILURE, without queueing or running it early.
This removes the missing-method crash without pretending background media
monitoring works. Ordinary foreground work still uses the existing scheduler.
JobParameters exposes the real job ID and returns no assigned network. Network
constraints are not implemented by this scheduler; this is a compatibility
limitation, not a claim that constrained background work is supported. The
nullable API contract is described in the [Android JobParameters reference](https://developer.android.com/reference/android/app/job/JobParameters#getNetwork()).

The common native-density window/content handling includes the ATL patch
originally developed alongside `mortelil/atl-shelf` (commit `62ee446`). It keeps
activity stacks, dialogs and native touch coordinates in the same coordinate
space. Existing scale settings remain explicit; this does not claim complete
Android display/inset behavior.
