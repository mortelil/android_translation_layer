# Companion-package native library metadata

Regression: a metadata-only `createPackageContext(package, 0)` and the preceding
PackageManager lookup returned a null `ApplicationInfo.nativeLibraryDir`.
Additionally, the code-loading path discarded `ATLNativeLibs.publishOrFail`'s
immutable generation directory and returned its old staging directory. This
broke microG's Dynamite provider loader before it could initialize Conscrypt.

Build inside Alpine using the candidate build, then install the fixture in a
**private** profile's `_installed_apks_` directory:

```sh
sh tests/package-native-metadata/build.sh build-mobile /tmp/native-metadata
mkdir -p /tmp/native-metadata-profile/_installed_apks_
cp /tmp/native-metadata/org.atl.nativefixture.apk /tmp/native-metadata-profile/_installed_apks_/
ANDROID_APP_DATA_DIR=/tmp/native-metadata-profile \
  sh scripts/mobile/run.sh /tmp/native-metadata/probe.apk
```

Set ATL_PREFIX/ATL_CORE_JAR/ATL_BUILD_DIR as required for your runtime. Success is
`PACKAGE_NATIVE_METADATA_PASS checks=14` and exit zero. This x86_64 fixture uses
a one-byte library marker, not executable native code. It tests publication and
metadata, not dlopen. It checks PackageManager first, then resource-only and
code-inclusive contexts, file contents, APK path and stable generation identity.
Run `sh tests/native-libs/run.sh` for extraction/security failure-path coverage.

Additional integration measurement (not required by this self-contained test):
a private no-account profile using microG 250932006 successfully created the
providerinstaller Dynamite context, installed its TLS provider and returned
HTTP 200 from the public ReturnYouTubeDislike votes endpoint with default TLS
validation. No TLS trust rules were changed. One initial integration run had a
native SIGSEGV; the debugger rerun and subsequent ordinary run succeeded. This
patch does not claim to fix that intermittent crash, video HTTP 413, IPv6
routing, or Google account login.
