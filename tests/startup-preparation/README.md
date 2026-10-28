# Startup preparation

`android-translation-layer APP.apk --prepare` populates ART's startup cache
without creating an Application, activity, provider or service. It needs no
display server. Exit 0 and `ATL PREPARE OK:` mean the startup dex artifacts
passed ART's up-to-date check. Compilation or configured signature-verification
failure must return nonzero; merely loading an interpreted dex is not success.

This is an opt-in first milestone: move compilation out of the first interactive
launch. It does not make compilation itself faster or promise a fully initialized
UI. Native library extraction, app callbacks, shaders and app-owned dynamic dex
remain launch-time work. Signature verification still runs on ordinary launch.

## Installation/update integration

After copying a single APK to its **final launch path**, and after finishing any
runtime update, call the normal launcher with `--prepare`. For the mobile wrapper:

```sh
scripts/mobile/run.sh /absolute/installed/app.apk --prepare
```

Use the same `ATL_PREFIX`, `ATL_BUILD_DIR`, `ATL_CORE_JAR`, `ANDROID_APP_DATA_DIR`,
`XDG_CACHE_HOME`, ABI, SDK and ghax settings as subsequent launches. Do not prepare
a temporary download path or a disposable cache. A different pathname can select
a different ART artifact even for identical bytes. APKM and multiple/split inputs
are deliberately excluded until their installed-path stability is defined.

Shelf is not automatically changed by this ATL patch. Its installer/updater can
invoke the command with its existing runtime environment after installation and
show preparation as a separate progress phase. A failure should remain visible;
it must not be reported as prepared. Normal launch retains ART's existing lazy
compilation/fallback behavior. Preparation is not an authorization to skip APK
signature checks at installation or launch.

ART owns dex checksum and runtime/boot-image validation; there is no new
mtime-based success marker. `ATLStartupPreparation` asks
`dalvik.system.DexFile.isDexOptNeeded()` after the real application loader has
opened the dex. In the pinned art_standalone this calls
`OatFileAssistant::IsUpToDate()` (art/runtime/native/dalvik_system_DexFile.cc).
Preparation uses the same actual class-loader construction as launch. This patch
does **not** change ART's existing dex2oat class-loader-context recording or
eliminate its classpath-mismatch warnings.

Launch and preparation serialize startup cache population using a persistent
`$XDG_CACHE_HOME/art/.atl-startup.lock` (GLib's user cache directory if unset).
The lock is close-on-exec, survives neither exit nor a killed process, and is
released before application constructors/callbacks so a parent may wait for a
service child. Never unlink the lock file while a process could hold it. This
protects the initial ATL startup path, not arbitrary later app-owned dex loading
or concurrent replacement of runtime files by a build/install tool. Complete
runtime updates before preparing/launching apps.

## Contract and regression tests

Inside Alpine, with the same private runtime environment used for builds:

```sh
ATL_BUILD_DIR=/absolute/build \
ATL_PREFIX=/absolute/runtime \
ATL_CORE_JAR=/absolute/runtime/share/art/core-all-hostdex.jar \
python3 tests/startup-preparation/run.py /absolute/new-test-output
```

Use a fresh output directory. Requirements: Java 8, dx, aapt, Python 3, xvfb-run,
and the existing ART/runtime dependencies. The synthetic APK is unsigned; tests
omit the external verifier only for this fixture, then separately exercise an
explicit verifier rejection. Real-app measurements must keep verification enabled.

The tests check:

- preparation with DISPLAY/WAYLAND_DISPLAY absent;
- no app static initializer, constructor, Application or provider callback;
- repeat preparation and actual launch reuse the artifacts without dex2oat;
- changed dex at the same APK path recompiles and executes the new version;
- changed runtime dex at the same ghax path recompiles, then is reused;
- configured verifier failure and incompatible CLI options fail;
- both preparation and ordinary launch block before VM startup on the cache lock;
- simultaneous fresh-cache preparation succeeds with only one compiler producer;
- a deliberately failing compiler cannot produce a false preparation success.

Also run `tests/service-owner` against the candidate to check cross-package and
child-to-parent services. Measure real apps separately: test cold launch, then
prepare a separate fresh profile and launch it. Report preparation time separately
from interactive startup and distinguish Activity.onCreate from actual UI output.
