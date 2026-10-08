# Matrix copy constructor regression tests

`new Matrix(source)` must copy all nine Android matrix values into independently
owned storage. `new Matrix()` and `new Matrix(null)` must produce identity matrices.
Previously, ATL's native constructor ignored `source` and always created identity.
For example, copying the transform `x' = 2x + 17, y' = 3y + 23` mapped `(4, 5)` to
`(4, 5)` instead of `(25, 38)`.

This affects any caller that copies a transform before adjusting it, including
image editors, custom drawing code, and embedded native views. The implementation
uses Graphene's matrix copy operation and retains the existing allocation and
finalizer ownership. It changes no matrix concatenation, rendering, or app-specific
behavior.

## Build and run

Use the same environment and library paths that run your ATL build. A working GTK
display is needed to start ATL, although these tests do not create an Activity.
Run Alpine-built binaries inside Alpine, not directly on a glibc host.

The runner prepends the selected build directory to `LD_LIBRARY_PATH`, so the
executable's native dependencies are taken from that build rather than an installed
ATL. `RUN_FROM_BUILDDIR` alone does not guarantee this. The rest of the dependency
search path is inherited; record it when reporting results.

Requirements in addition to ATL's build dependencies: `aapt`, `dx`, `javac`,
`pkg-config`, `timeout`, and `realpath`. On Alpine, `android-build-tools` supplies
`aapt`. `JAVAC` can select a compiler compatible with the installed `dx` (for
example `/usr/lib/jvm/java-1.8-openjdk/bin/javac`). The build script uses ATL's
`hax.jar` and ART's existing JUnit classes; it downloads no dependencies.

From the repository root, after configuring `builddir`:

```sh
meson compile -C builddir
meson compile -C builddir test_runner.jar
tests/graphics/matrix-copy/build.sh builddir build/matrix-copy
tests/graphics/matrix-copy/run.sh builddir \
  build/matrix-copy/matrix-copy-tests.apk build/matrix-copy/run
```

The runner sets a separate app-data directory below its output directory. It
installs only the test APK there, without a desktop entry, then runs ATL's existing
`android.test.InstrumentationTestRunner` with an explicit test-class filter.
No Immich APK, account, network request, photo, or device permission is required.
The generated APK contains only this test class and its manifest.

Success requires **`OK (6 tests)`**, not just exit status zero. `run.sh` checks this
and fails on timeouts, launch failures, incomplete runs, or failing assertions.
The default per-command timeout is 60 seconds; override `ATL_TEST_TIMEOUT` for a
slower development device. Logs are `install.log` and `result.log` in the selected
output directory.

## Coverage

- Default and null-source constructors produce identity.
- Copying identity preserves identity and returns a distinct Java object.
- An affine matrix retains scale, shear, reflection, and translation values.
- A perspective matrix retains all nine values, including a non-unit final value.
- Mutating the source leaves the copy unchanged, and vice versa.
- Applying a copied transform maps a point to independently calculated coordinates.

The affine, perspective, mutation, and point-mapping tests are expected to fail
with the old identity-only native constructor. The two identity tests guard its
existing behavior. The suite targets the constructor contract; it is not a claim
that all ATL graphics or other Matrix operations conform to Android.

## Origin and license

The test source, manifest and scripts were written for this regression with AI
assistance; they are not imported AOSP/CTS files. They use the repository's GPLv3
license, identified as `GPL-3.0-only` in their headers. The native fix reuses the
Graphene copying operation already used by `Matrix.native_set` in the same ATL
file. No Android framework Java class is imported or modified by this patch.
