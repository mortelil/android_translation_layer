# Fragment.InstantiationException contract

This patch exposes the existing Android exception API. It does not implement
Fragment.instantiate, transactions, tag lookup or lifecycle callbacks.

The local lifecycle candidate inherited directly from RuntimeException. Android
uses AndroidRuntimeException, so catching AndroidRuntimeException must also catch
this exception. The probe tests that direct superclass, catch behavior, public
(String, Exception) constructor, retained message/cause identity and null values.

Reference and adapted exception definition: AOSP frameworks/base commit
[99b01a65cc4c104933788b3143285ab6bae65827](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/app/Fragment.java),
android-16.0.0_r1. Its Apache-2.0 copyright/license notice is retained next to
the adapted nested class.

Inside Alpine, run `tests/fragment-instantiation-exception/build.sh "$ANDROID_JAR" /absolute/output`.
Sign the APK with the host SDK. Run the same signed APK on Android and ATL with
fresh logs and explicit ATL_BUILD_DIR/runtime paths (see ../paint-measure/README.md).
Component: org.atl.tests.fragmentexception/.ProbeActivity.
Require FRAGMENT_EXCEPTION_PASS and no FRAGMENT_EXCEPTION_FAIL. A missing class
or incorrect superclass is a failure, not a skipped API.
