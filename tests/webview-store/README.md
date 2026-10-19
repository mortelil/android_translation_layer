# App-private WebView HTTP credentials

Build inside atl_dev with `sh tests/webview-store/build.sh "$ATL_BUILD_DIR" OUT`.
Launch OUT/store.apk twice with the same private ANDROID_APP_DATA_DIR using
scripts/mobile/run.sh. Require WEBSTORE_PASS reload=false checks=11, then
WEBSTORE_PASS reload=true checks=13. The test uses only synthetic credentials.

Covers same-app identity, separate app files with the same preference name,
replacement, null host, nullable credential pairs, XML-safe NUL-containing keys,
key-boundary separation, clearing, deprecated form APIs not clearing HTTP auth,
and persistence into a new process. This is an ATL-specific storage isolation
probe, not an Android provider comparison; it overrides ATL's preferences path.

The OMP 9203f3a2 implementation was reworked: an abstract public API matching AOSP,
app-scoped singleton/preferences, XML-safe keys, atomic credential updates and
explicit write failure. Public API reference: frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/webkit/WebViewDatabase.java (Apache 2.0 header retained).

Limits: no automatic WebKit authentication or form autofill; no simultaneous
multi-process writers or encrypted credential database. Persistence is tested
across sequential process restarts, not concurrent processes.
