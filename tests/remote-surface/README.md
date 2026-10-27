# Cross-process Surface presentation

Run inside the development container, with ATL_PREFIX, ATL_CORE_JAR and
ATL_BUILD_DIR selecting the candidate runtime (see scripts/mobile/environment.sh):

```
sh tests/remote-surface/build.sh "$ATL_BUILD_DIR" /tmp/remote-surface-apk
sh tests/remote-surface/run.sh /tmp/remote-surface-apk/probe.apk /tmp/remote-surface-run
```

The service runs in a separate process. The test asserts its PID differs, paints
through EGL, then checks the actual GTK consumer texture. It also covers empty
parcelables, forwarding through a native Surface roundtrip, return-value release,
rejection of an unsealed frame FD, consumer destruction, and dynamic KHR/EXT
swap-with-damage lookup (including bad arguments and EGL error reset).

## Implementation and scope

Android transports a producer capability, not a native pointer. Reference:
[AOSP Surface.java, android-16.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/view/Surface.java).
No AOSP implementation is copied by this change. ATL's private Binder protocol
transfers sealed memfd RGBA frames from an EGL pbuffer to the widget-owning
process. Dimensions, size and seals are validated before reading; frames are
limited to 64 MiB and the owner coalesces queued frames. This is CPU readback,
not Android's BufferQueue wire protocol, GPU buffer sharing or zero-copy video.
Damage hints use full-frame presentation. Canvas locking on a remote Surface,
remote SurfaceTexture consumers, fences and automatic live pbuffer resizing are
not implemented. The producer must recreate its EGL surface for size changes.

Firefox integration evidence uses an isolated profile and a local HTTP fixture,
plus public HTTPS. Never use private browser data for this test.
