# Immutable EGL snapshots

Run inside Alpine with access to a real GTK display and an EGL driver supporting
`EGL_MESA_image_dma_buf_export`:

```sh
sh tests/egl-snapshot/run.sh /tmp/egl-snapshot-results
```

This compiles the production implementation. It checks GLES2/3 pixels (including
premultiplied alpha and unequal image regions), GL bindings and pending errors,
source overwrite/resize/destruction, survival after producer-context destruction,
repeated descriptor cleanup, worker-to-GTK import and bounded CPU fallback when
GTK is not dispatching. A linker wrapper rejects or delays GTK import to exercise
failure and late-completion ownership, without production fault-injection hooks.
Every successful import's real release callback must run exactly once. This is a
hardware integration test: unsupported dma-buf hardware is not reported as PASS.
Also run `tests/egl-readback/run.sh`, `tests/egl-headless/run.sh` and
`tests/egl-lifecycle/run.sh` for the existing fallback/lifecycle contracts.

## Production behavior

Composited EGL surfaces normally try a separate GPU copy exported as dma-buf.
GTK owns the snapshot descriptors until its texture is destroyed. No application
back buffer is leased to GTK, and no widget/surface/context reference cycle is
introduced. The GPU copy finishes before export; this is not a zero-copy or
asynchronous-fence implementation. TextureWindow/pbuffer CPU consumers are unchanged.

Import occurs on GTK's default main context. A worker waits at most 8 ms for
import, then uses the original frame's CPU readback. Late imports release their
resources. Failure backs off for one second per surface. `ATL_EGL_DMABUF=0`
forces the previous CPU presentation path. `ATL_DEBUG_SURFACE=1` reports which
path each frame used. Existing EGL errors and GL state are preserved; context
loss remains visible to the application.

GTK ownership contract used as reference (no GTK implementation copied):
https://docs.gtk.org/gdk4/method.DmabufTextureBuilder.build.html

## Validation, 2026-10-28

Native tests pass on Mint's Alpine toolbox (x86_64, X11) and Nura (aarch64,
Wayland), including rejection, timeout and late successful import cleanup.
LocalSend on PC renders both Receive and Send, and continues drawing after a
1920x986 -> 1144x648 render-size change. Dispensa and Rhythm retain their native
GTK interfaces; this does not claim complete functionality of those apps.

Two alternating 10-second LocalSend trials at render scale 2 measured process
CPU seconds of 3.91/4.19 (CPU readback) versus 1.35/1.31 (GPU snapshots), with
roughly 286-290 presented frames per run. Scale 1 showed little difference.
These are short end-to-end process measurements, not universal speedup claims,
and they do not measure startup preparation or network speed.

With Nura's screen on (Shelf scale 2.65), the equivalent alternating trials used
8.23/8.02 CPU seconds and presented 161/161 frames on the CPU path; GPU trials
used 7.34/7.22 seconds and presented 205/207 frames (196/195 dma-buf frames;
the remainder fell back). The final app-only screenshot showed LocalSend's
Receive UI correctly. Earlier screen-off measurements were discarded as a
performance comparison because the compositor throttled drawing. No login or
file transfer was performed. This does not establish video-codec performance;
MediaCodec/TextureWindow presentation is outside this change.
