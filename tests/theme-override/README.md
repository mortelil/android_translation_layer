# Configuration override and inherited theme regression

Run `build.sh BUILD_DIR OUTPUT_DIR` inside Alpine, then launch the generated
`theme-override.apk` with the same ATL build in a disposable profile.
Expected exit status: 0, with `THEME_OVERRIDE_PASS` in stdout.

Tests cover immutable override input, override ordering, parent/sibling resource
isolation, inherited application booleans and string references, night-qualified
resource selection, and an application attribute pointing to a framework icon.

Configuration copies must share the native ApkAssets objects: AOSP Theme::SetTo
identifies shared resources by object identity, not by pathname. AssetManager
configuration state itself must remain independent.

The framework-icon check additionally requires the art_standalone
`fix/theme-copy-shared-assets` dependency patch. Alpine's
libandroidfw 0_git20251009-r2 drops that reference even with shared assets.
For local build-tree testing, place the patched `libandroidfw.so` in the ATL
build directory (the mobile runner puts it first in LD_LIBRARY_PATH).
Rebuilding ATL alone does not apply the dependency fix.
