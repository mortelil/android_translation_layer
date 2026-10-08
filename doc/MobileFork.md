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
  pc:alsa pc:glib-2.0 pc:gtk4 pc:gudev-1.0 pc:libportal pc:openxr \
  pc:vulkan pc:webkitgtk-6.0 pc:libsecret-1 ffmpeg-dev \
  bionic_translation-dev art_standalone-dev libandroidfw-dev
```

Alpine's edge/testing repository must be enabled for the ATL dependencies; see
the upstream [build guide](Build.md). A PulseAudio-compatible output can require
Alpine's `alsa-plugins-pulse` package. The scripts do not install system packages.

Inside the ATL checkout, inside Alpine:

```sh
JOBS=4 scripts/mobile/build.sh
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
ATL_DISABLE_FULLSCREEN=1 ATL_RENDER_SCALE=2.65 scripts/mobile/run.sh /absolute/path/to/app-arm64.apk
```

Scale 2.65 was used on one phone; choose a value appropriate for your screen.
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
