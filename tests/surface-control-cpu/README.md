# CPU SurfaceControl milestone

This replaces the unmerged transaction bookkeeping prototype with a real,
limited rendering path. CPU RGBA8888 buffers are copied into GDK textures and
composed in the actual SurfaceView snapshot. Parent/child ownership, geometry,
reparenting, consumed transactions and completion callbacks are implemented.
The API contract reference is `libandroidfw/include/android/surface_control.h`
in art_standalone commit `327b9c2e5ec49176c073df9f7995d7faf69153b5` (NDK).

Completion requires a snapshot containing the transaction followed by **complete
GDK frame timings with a positive presentation time**. Applying or snapshotting
alone never triggers it. Original AHardwareBuffers are released after copying;
therefore the previous-buffer release fence is truthfully -1 at completion.

## Scope and limitations

- Only ATL's CPU RGBA8888 HardwareBuffers, one layer, premultiplied sRGB/unknown
  dataspace, no acquire fence. CPU BLOB behavior remains unchanged.
- GPU buffers/import, synchronization fences, other dataspaces and the rest of
  the SurfaceControl API are not implemented. Unsupported batches are rejected
  atomically with a warning and **no completion**; void NDK setters cannot return
  an error. This does not make GPU clients such as Chromium fully supported.
- createFromWindow requires a real GTK SurfaceView on the GTK thread. SurfaceTexture
  and direct EGL windows return NULL/ENOTSUP. Reparent cannot target a window.
- Presentation feedback depends on the compositor. A bare Xvfb reports complete
  timings with time 0: pixels appear but completion remains pending. A hidden
  widget may likewise wait until shown. Destruction cancels pending callbacks.
- Verified on Mint's X11 compositor inside Alpine. Wayland/Nura is **not tested**.

## Test

Inside Alpine, with the desktop display available:

```sh
sh tests/surface-control-cpu/run.sh BUILD_DIR RUNTIME_PREFIX OUTPUT_DIR
```

Uses the actual libandroid, libtranslation_layer_main and SurfaceView, and reads
window pixels through XGetImage. Asserts green → blue → removed, callbacks after
apply (not synchronous), transaction reuse/deletion, 100,000 cross-thread reference
pairs, queued-cycle rejection, rejected-fence ownership, atomic rejection of an
unsupported buffer, and destruction without a fabricated callback. Test launcher
hooks abort if accidentally reached; no rendering/completion function is mocked.
A timeout is a failure, not a pass. Bare Xvfb is also a useful negative test: it
must show pixels **without** producing a completion callback.

Further coverage needed: Wayland presentation, mixed geometry/alpha and hierarchy
pixel oracles, extended cross-thread stress and GPU/fence integration before
expanding the supported milestone.
