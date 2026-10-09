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
reference ownership, write permissions, and rejection of unsupported GPU usage
and fences. `tests/mobile-smoke` additionally tests Java/native construction,
properties and idempotent close. GPU images, EGL/Vulkan import, IPC, multi-plane
formats and Java parcel transport are not implemented.

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
These functions do not implement `ASharedMemory_setProt` or Java SharedMemory
transport. In particular, no success-returning protection stub is exported.
API reference: https://developer.android.com/ndk/reference/group/memory
Implementation and tests were authored with AI assistance from the API contract;
no AOSP implementation was copied.
