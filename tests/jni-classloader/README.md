# JNI classloader boundary regression

This replaces the proposed system-classpath APK injection (OMP 041b11fe) with a
contract test. A raw FindClass failure on an attached native thread is not proof
that app classes belong on the system classpath. Gecko's GetClassRef already
falls back to the loader cached from GeckoThread.class.getClassLoader().

The same APK/native library is run on Android API37 x86_64 and ATL:
1. FindClass from a Java-entered JNI method returns the application's class.
2. A pthread attached with AttachCurrentThread, with no managed stack frame,
   cannot FindClass that application class.
3. On that thread, the cached application loader's loadClass resolves exactly
   the original Class object (not merely a class with the same name).
4. The system loader cannot load the application class.
5. A second app loader loads a distinct Class object from the same dex.

Both platforms must report JNI_CLASSLOADER_PASS. Injecting the APK into the
system classpath violates rows 2, 4 and 5. This does not establish the cause of
Firefox's later crash or prove that Gecko initialized its cached loader correctly.
Do not infer those claims from this synthetic probe.

## Build and run

The tiny C helper imports only pthread_create/join through Android libc.so. It
uses JDK JNI headers with the standard JNI ABI and no host CRT. The explicit
Android libc link input gives it DT_NEEDED libc.so, resolving to Android Bionic
on the emulator and ATL's Bionic translation library on Linux. No emulator libc
is bundled in the APK. This fixture targets x86_64 only.

On the host, obtain the reference library (or use an NDK x86_64 libc.so):

```
adb -s emulator-5554 pull /apex/com.android.runtime/lib64/bionic/libc.so /tmp/android-libc.so
```

In atl_dev, with JDK8 on PATH:

```
sh tests/jni-classloader/build.sh "$ATL_BUILD_DIR" /tmp/jni-loader /tmp/android-libc.so
ANDROID_APP_DATA_DIR=/tmp/jni-loader/data XDG_CACHE_HOME=/tmp/jni-loader/cache \
  timeout 40 dbus-run-session -- xvfb-run -a sh scripts/mobile/run.sh /tmp/jni-loader/probe.apk
```

Sign the APK with a development key, install it on Android and launch
org.atl.loaderprobe/.Probe. Read only log tag LoaderProbe for results.
Reference: art_standalone 3093c7bdbb90c4b93b69f2328078918c6b632943,
art/runtime/jni/jni_internal.cc GetClassLoader/FindClass (AOSP-derived ART).
A managed frame supplies its declaring class loader; without one ART uses the
system loader. No ART/AOSP implementation copied.
