# surface-lifecycle probe

Deterministic, marker-based probe for the bounded `android.view` surface fixes:

* `Window.setColorMode()` / `getColorMode()` (`COLOR_MODE_DEFAULT`,
  `COLOR_MODE_WIDE_COLOR_GAMUT`, `COLOR_MODE_HDR`; out-of-range values are
  rejected with `IllegalArgumentException` and leave the recorded mode alone);
* `TextureView` real `SurfaceTexture` lifecycle: `onSurfaceTextureAvailable` ->
  `onSurfaceTextureSizeChanged` -> `onSurfaceTextureDestroyed` in order,
  `getSurfaceTexture()` returning the real (owned) texture while the view is
  available and never a dummy after the listener released it, and a fresh
  texture after re-attach;
* the cleanup path: an injected view whose `onDetachedFromWindow()` throws must
  propagate the original Java exception to the `removeView()` caller instead of
  aborting the VM (`JNI CallVoidMethod called with pending exception` ->
  SIGABRT in `ViewGroup.native_removeView`).

## Why the checks are deferred

ATL attaches the window's content view **after** `Activity.onCreate()` returns
(the GTK widget hosting the window is realized later), so a probe that checks
`isAvailable()` right after `setContentView()` sees an unattached view. All
attach-dependent checks therefore run from `Handler.postDelayed` (also triggered
early by `onWindowFocusChanged`). Each check prints one line
`SURFACE_LIFECYCLE ok|FAIL <name>` and the run ends with

```
SURFACE_LIFECYCLE RESULT PASS|FAIL checks_failed=N size_change_path=... \
  post_destroy_getter=... detach_throws=N events=[...] failures=[...]
```

plus `<files dir>/results.json` (the runner's result artifact).

The expected throw is *described* on stderr once by the framework
(`java.lang.RuntimeException: probe-detach-throw` from `ExceptionDescribe()` in
`WrapperWidget.c:root_changed_cb`) — that is the fix reporting the pending
exception, not an uncaught crash: the probe catches the very same object and
`detach_throws=1`.

## Build / run

```sh
python3 ~/.omp/profiles/atl-compat/agent/resources.py --team setup --kind build --timeout 900 -- \
  toolbox run --container atl_dev sh -lc \
  "sh <REPO>/tests/surface-lifecycle/build.sh <REPO>/build-mobile <REPO>/tests/surface-lifecycle/build"

ATL_PROBE_ENV=ANDROID_APP_DATA_DIR \
  ~/ATL/agent-work/COMPAT-FIXES/bin/run.sh <REPO> <REPO>/build-mobile \
  <REPO>/tests/surface-lifecycle/build/surface-lifecycle.apk <OUT_DIR> \
  "SURFACE_LIFECYCLE RESULT" 120 probe
```

## Fixture identity (fullscreen photo)

`verify-fixture.sh SHOT.png FIXTURE.png [LABEL]` proves that a fullscreen capture
really shows the synthetic fixture (`ATL-test-pattern.png`, 640x480: ~100 px
`#2453BA` border around a `#FFFF70` core at +100+100) rather than merely "some
non-black pixels". It recomputes the fixture's core rectangle, requires the same
aspect ratio and an isotropic scale, samples the capture at the fixture's known
relative coordinates (border band + field centre), and compares a normalised
64x48 nearest-neighbour raster of both images (region md5 + differing pixel
count). Exits 0 on PASS, 1 on FAIL.

```sh
sh tests/surface-lifecycle/verify-fixture.sh <OUT_DIR>/shot-photo.png \
  /home/morten/ATL/compat-fixes/data/surface-media/ATL-test-pattern.png
```
