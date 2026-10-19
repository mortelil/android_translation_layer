# Shared-memory Android/ATL comparison (x86_64)

Build inside atl_dev, supplying libc.so and libandroid.so pulled from an x86_64
Android emulator (only link inputs; neither is packaged):

```
JAVA_HOME=/usr/lib/jvm/java-1.8-openjdk sh tests/shared-memory-android/build.sh \
  build-mobile /tmp/shared-probe /path/to/libc.so /path/to/libandroid.so
```

Sign `/tmp/shared-probe/probe.apk` with a development key, install on the
emulator, and launch `org.atl.sharedmemoryprobe/.Probe`. Run the same signed APK
with ATL. Success is `SHARED_MEMORY_RESULT=0`; the log also records the backend.
The process exits after testing; no accounts, network or user data are accessed.
Nonzero results 1–11 identify the numbered CHECK in probe.c.

Tests verify READ restriction succeeds, restoring WRITE fails with EINVAL,
new shared writable mappings and read-to-write mprotect fail, and existing
writable mappings retain access. The final two checks intentionally distinguish
legacy ashmem (READ/EXEC revocation) from AOSP's memfd backend (write-only
revocation). F_GET_SEALS identifies the backend. Executable mapping permission
is measured before restriction so unrelated platform policy is not mistaken for
setProt's effect. This is not a claim that ATL implements legacy ashmem security.

2026-10-19: Android emulator API 37 uses ashmem; ATL uses memfd. Both passed their
respective contracts. Native tests additionally exercise dup/fork and resize
seals. Firefox 157.0.1 reached Welcome, and Continue reached the start page on
x86_64/X11. Browsing, ARM and long-running stability were not verified.

Behaviour reference: AOSP system/core commit
`d3e91011ef767c231ec9c374cfc5c18657566b9c`,
`libcutils/ashmem-dev.cpp`, `memfd_set_prot_region()`.
Implementation/test code is original ATL work; AI-assisted.
