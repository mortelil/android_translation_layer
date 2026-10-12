# WebView JavaScript support

`evaluateJavascript` now evaluates in the real WebKit page and asynchronously
returns JSON text on the main Java Looper. Null/undefined and evaluation errors
produce `"null"`; a null callback still executes the script. `loadUrl` with a
`javascript:` URL uses the same evaluator. Unicode crosses JNI as UTF-16/UTF-8,
not modified UTF-8. Invalid UTF-16 input is rejected explicitly.

`addJavascriptInterface` injects public `@JavascriptInterface` methods at
document start into each frame. Java methods run on one serial background
thread per WebView, including stable ThreadLocal state across calls. Return
values are synchronous from JavaScript's perspective. Interface updates and
removal affect subsequent document loads; current documents keep their bound
objects until navigation or destruction. A new main document releases older
snapshots; snapshots created during a document are retained until then.

## Transport and limits

WebKit's asynchronously handled script-dialog signal provides the synchronous
JavaScript transport. Injected closures capture the native prompt function and
a random document-generation capability. Reserved bridge prompts are handled
internally without displaying a dialog. Requests without a valid capability
cannot invoke Java. Reflection permits only public annotated methods, including
those on non-public application classes, never arbitrary Object/getClass access.
The GTK thread remains available while the Java worker runs. Ordinary page
dialogs are not handled by this bridge.

Supported arguments: null references, strings, booleans, numeric primitives and
one-dimensional primitive arrays. Supported returns: void, null, strings,
booleans and finite numeric/character values. Ambiguous overloads, arbitrary
Java object proxies, object/array returns and JavaScript-object arguments are
not implemented and return a JavaScript error, not a fabricated success. This
is not complete Chromium LiveConnect coercion. The unsafe pre-API-17 behavior
of exposing unannotated public methods is deliberately not reproduced.
WebView JavaScript APIs currently require ATL's main UI thread.

As with Android, injected methods are available to all frames, without an
origin-based allowlist. Only expose objects appropriate for all content loaded
in that WebView. `destroy()` clears retained bridge objects, cancels evaluations,
removes injection scripts and stops loading. It does not implement all unrelated
WebView destruction/layout semantics. Calls after destroy are rejected.

`ATL_UGLY_ENABLE_WEBVIEW=1` remains required. These APIs explicitly fail when the
backend is disabled instead of silently succeeding. Page-load failures and web
process termination now produce diagnostics; script evaluation failures report
only an error code. URLs, script contents, Java exception messages and bridge
arguments/results are not logged. This does not add general console logging or
implement every WebViewClient error callback.

API reference: [Android WebView](https://developer.android.com/reference/android/webkit/WebView).
Native transport follows the installed WebKitGTK 6.0 headers and GIR docs for
evaluate_javascript, user scripts and asynchronous script-dialog handling.
The implementation is original ATL code; no app-specific callback shortcut or
third-party JavaScript library is copied in.

## Validation (Alpine x86_64, 2026-10-12)

Inside Toolbox, with ATL_PREFIX and ATL_CORE_JAR pointing at the development
runtime, from the checkout:

```sh
sh tests/web-javascript/build.sh build-webview /tmp/web-javascript
dbus-run-session -- xvfb-run -a sh tests/web-javascript/run.sh \
  build-webview /tmp/web-javascript/web-javascript.apk /tmp/web-javascript-result
```

The local-only fixture checks real evaluation, JSON values, Unicode, null/error
results, UI callback threading/asynchrony, synchronous Java results, primitive
arrays, background-thread/ThreadLocal behavior, unannotated-method exclusion,
forged-capability rejection, same-origin and opaque cross-origin iframes,
removal on navigation, view isolation, javascript: execution and destruction.
Synthetic secret strings in exceptions must not appear in logs.

Full mobile-smoke passed. microG reached Google's email form and accepted dummy
input; LocalSend Send/Settings navigation and normal shutdown passed. No real
Google login was performed; completion of the user's account login is still
unverified. Signal was not started. Nura was not tested in this change.
Private artifacts: `/home/morten/src/webview-tests/`, not committed.
