# PendingIntent OS/target-SDK compatibility

Regression: Firefox targets a modern SDK but ATL defaults its reported OS level
to the APK's minimum (26). Requiring Android 12 explicit mutability solely from
targetSdkVersion caused startup to throw before its Activity was created.

Compatibility changes must exist in the emulated OS as well as apply to the
app's target. Reference: AOSP frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/app/PendingIntent.java, checkPendingIntent, explicit mutability
introduced in S and mutable-implicit restriction introduced in U. No code copied.

Inside atl_dev, JDK8 on PATH and candidate ATL_BUILD_DIR/ATL_PREFIX/ATL_CORE_JAR:

```
sh tests/pending-intent-sdk/build.sh "$ATL_BUILD_DIR" /tmp/pending-sdk-apks
sh tests/pending-intent-sdk/run.sh /tmp/pending-sdk-apks /tmp/pending-sdk-results
```

All fixtures target SDK35. APK minimum SDKs 26, 31 and 34 select ATL's default
reported runtime versions. Six rows check absent flags, immutable, explicit
mutable, implicit mutable, conflicting flags and explicit unsafe-implicit opt-in.
The runner asserts each actual runtime SDK and PASS marker. The SDK26 fixture
reproduces the original rejection on uncorrected main.

Any fixture can also be signed and installed on Android. Start
org.atl.pendingsdk/.Probe and inspect PendingSDK log output; expectations use the
actual Android OS version. API37 reference passed all six rows. This does not
claim that old Android emulator images were run. The existing real two-process
PendingIntent and notification-action tests remain complementary regressions.
