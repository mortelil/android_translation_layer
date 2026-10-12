# Profile-local cache and native extraction

Inside `atl_dev`, build with:

```
sh tests/cache-native/build.sh build-revanced /tmp/cache-probe
```

Run `/tmp/cache-probe/cache-native.apk` through `scripts/mobile/run.sh` with
`ATL_BUILD_DIR` pointing to the build being tested and a fresh
`ANDROID_APP_DATA_DIR`. Expect `CACHE_NATIVE_PASS` and exit 0. Repeat with a
different profile: the reported cache must be under that profile, not shared
`/tmp/atl_cache`. No Android SDK/NDK is required; the tiny JNI ELF has no libc
imports and uses the container's native architecture.

The probe checks activity/application consistency, cache placement beside
`files`, extraction to a nested directory, actual System.load and a JNI call.
The previous implementation fails the placement assertion. A ReVanced test
also confirmed that GmsCore's extracted Conscrypt library now loads; certificate
validation and app startup are separate checks, not proved by this probe.

Old `/tmp/atl_cache` contents are neither migrated nor deleted. Apps repopulate
cache on demand. This does not change the existing shared-process behavior of
foreign package contexts or external-cache emulation.
