# Native buffer, shared-memory and audio compatibility tests

Run inside Alpine with a C compiler, ALSA headers and a JDK:

```sh
JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk sh tests/native-compat/run.sh
```

The tests use a temporary ALSA configuration whose default device is `null`.
They never access an actual microphone. They check callback delivery, stop and
restart, builder lifetime and validation errors. Real device negotiation,
physical recording, latency and disconnection recovery still need testing.

The hardware buffer test checks CPU BLOB allocation, shared memory access,
reference ownership, write permissions, rejection of unsupported GPU usage and
fences, and the cross-process descriptor transfer of
`AHardwareBuffer_sendHandleToUnixSocket`/`_recvHandleFromUnixSocket` (the buffer
is backed by a memfd, so the receiver maps the same pixels). `tests/mobile-smoke`
additionally tests Java/native construction, properties and idempotent close. GPU
images, EGL/Vulkan import, multi-plane formats and Java parcel transport are not
implemented.

The initial AAudio implementation supports shared callback input using PCM16 or
float through ALSA's default device. It does not support output, synchronous
read/write, Android effects associated with input presets, or the complete
AAudio query/state API. Unsupported output and non-callback streams return an
error; they do not claim to play or record anything.

API contracts:
- https://developer.android.com/ndk/reference/group/a-hardware-buffer
- https://developer.android.com/ndk/reference/group/audio

`ASharedMemory_create` and `ASharedMemory_getSize` use fixed-size, sealable
Linux memfds. Tests cover zero initialization, size, invalid inputs, duplicate
descriptors, cross-process mappings, resize rejection and mapping lifetime after
all descriptors close. Names are truncated to Linux's 249-byte memfd label limit.

`AImageReader_acquireNextImageAsync`, `AImage_deleteAsync`, `AMediaCrypto_*` and
the `AMediaDrm_*` family are implemented against the NDK headers of AOSP tag
android-17.0.0_r1. ATL has no ImageReader producer and no DRM plugin, so they
report the documented failure instead of fabricating objects: the async acquire
answers `AMEDIA_ERROR_INVALID_PARAMETER` (null reader/image) or
`AMEDIA_ERROR_UNSUPPORTED` and leaves the fence at the NDK's "no fence" value -1,
`AImage_deleteAsync` releases through the synchronous path, `AMediaCrypto_new`
returns NULL and the scheme query `false`, and `AMediaDrm_createByUUID` returns
NULL while the rest of the family answers `AMEDIA_ERROR_INVALID_OBJECT`,
`AMEDIA_ERROR_INVALID_PARAMETER`, `AMEDIA_ERROR_UNSUPPORTED` or
`AMEDIA_DRM_SESSION_NOT_OPENED` exactly where AOSP does when the plugin is
absent. `ndk-header-check.sh` downloads those headers from gitiles (the
prototypes are not hand-copied), compiles `test-media-drivers.c` with them and
runs the checks; it needs curl and network access, and is not part of `run.sh`.
No key request, session id or provision request is ever fabricated.

`ASharedMemory_setProt` supports removing write access from future shared
mappings with F_SEAL_FUTURE_WRITE; existing mappings remain writable. Removing
read or execute permission returns ENOTSUP without modifying the region.
F_SEAL_EXEC only freezes mode bits and does not prevent executable mmap.
Widening a write-restricted region returns EPERM. Return is -1 with errno on
failure. Full ashmem read/execute revocation is not implemented.

sync_merge uses real kernel sync_file ioctls; it does not turn eventfd or arbitrary
file descriptors into fake GPU fences. Negative descriptor tests run everywhere;
a positive real-fence merge needs a GPU/sw_sync fixture and is not claimed here.

Font iterators and returned fonts own separate paths; both destruction orders
are tested. APerformanceHint reports no manager/session because there is no
scheduling backend. CPU hardware buffer transfers require fixed-size sealed
storage, validate geometry, set CLOEXEC, and close received descriptors even on
truncated/malformed messages. GPU import is still unsupported.

OMP sources: 0b9d6543, 5e15cc7d, 6a8a8c86, f5c885aa, ded2f7d6,
ee27df35, ccc198b8. Review repaired font double-free, rejected fake fences and
incorrect execute-protection claims, and hardened descriptor transport. Tests
were expanded accordingly; implementation and review were AI-assisted.
