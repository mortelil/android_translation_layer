# NativeWindow / Surface lifetime

Run inside atl_dev, with ATL_BUILD_DIR / ATL_PREFIX / ATL_CORE_JAR selecting the
candidate, and JDK8 tools on PATH:

```
sh tests/native-surface-lifetime/build.sh "$ATL_BUILD_DIR" /tmp/native-surface-apk
sh tests/native-surface-lifetime/run.sh /tmp/native-surface-apk /tmp/native-surface-results
```

The real JNI/GTK probe checks:
- NativeWindow -> Surface -> NativeWindow returns the same native handle, with
  one acquired reference per conversion, idempotent Java release and fallback
  finalization.
- Four native workers repeatedly acquire/release a retained handle (40,000 pairs).
- 300 complete texture wrapper lifetimes, including final release on a worker.
- A listener/Surface/SurfaceTexture cycle is collectible; native producers do
  not root Java consumers. Submission to released/collected consumers fails.
- Real SurfaceView attachment, native wrapper validity, removal while a producer
  is retained, cached width access after removal and worker-thread final release.
- Both ATL's composited path and ATL_DIRECT_EGL=1 on X11.

The native fixture inspects ATL reference counts, so it is intentionally not an
Android binary. Expected rejected submission to a released texture prints an
IllegalStateException; NATIVE_SURFACE_PASS is the success marker. A timeout or
missing marker is failure. Existing surface-lifecycle, egl-headless,
egl-lifecycle, egl-readback and mobile-smoke are complementary regressions.

## Contract and implementation

Reference: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/view/Surface.java constructor documentation and release/finalize;
NDK ANativeWindow_fromSurface / ANativeWindow_toSurface ownership contracts.
The Java Surface constructor explicitly says that retaining Surface does not
keep SurfaceTexture alive. This implementation uses weak Java/JNI consumer
references and retains the actual native producer for converted Surfaces. No
new AOSP implementation was copied; existing source notices remain intact.

GTK widget/display/parent references are retained until final native release.
Signal disconnection and backend destruction happen on the GTK thread, including
when a Java finalizer or renderer drops the last reference. First creation from
an ordinary widget Surface on a worker is marshaled to GTK; the GTK event loop
must remain running. An unrealized view abandons its producer rather than letting
resize callbacks access a destroyed backend.

Scope: no Surface parcel transport, full Android BufferQueue, Surface canvas for
widget-backed wrappers or new Vulkan support. Wayland subsurface cleanup is
implemented but not exercised by this X11 fixture. ARM/Nura was not tested in
this change. This does not claim that Firefox renders correctly.
