# Experimental WebView cookies and diagnostics

With `ATL_UGLY_ENABLE_WEBVIEW=1`, Android CookieManager and all WebViews in an ATL
process now use the same WebKitNetworkSession. Previously CookieManager queried a
separate SoupCookieJar, making cookies created during browser authentication
invisible to Android code. In particular microG reads the browser's completion
cookie via CookieManager; reaching the login page alone did not test this.

The session's data and cookie database live under the app's own `atl-webview/`
directory (mode 0700), with a separate cache subdirectory. ATL no longer uses the
desktop default WebKit session. Existing standalone `atl-cookies/` storage is
unchanged when WebView is disabled. There is no migration from either old store:
copying cookies from an unrelated desktop/global session would violate isolation.
Switching modes can require logging in again.

Cookie operations are marshalled to GTK's main thread. Worker callers wait for
the reply without running GTK; a synchronous call on the main thread pumps GLib,
so it can be reentrant. A 15-second cancellable request reports backend failure
rather than returning a made-up empty result. An unresponsive main event loop
cannot itself be recovered by this timeout. Java callback methods continue to
post their result to the calling Looper. Removal operates on a snapshot and does
not promise a transaction against simultaneous requests creating new cookies.

WebKit persists non-session cookies automatically. Its public API has no
synchronous durable flush equivalent, so explicit CookieManager.flush() currently
throws UnsupportedOperationException in WebView mode. Session cookies remain
session-only. Partitioned cookies, per-view third-party acceptance and legacy
store migration are not implemented. The global acceptance switch applies to the
shared network session; enabling it retains the no-third-party default policy.

Set `ATL_WEBVIEW_DIAGNOSTICS=1` for navigation event numbers (0 start, 1 redirect,
2 commit, 3 finish), resource start/finish and HTTP status codes, resource failure
codes, and generic JavaScript bridge failures. No URLs, cookie values, headers,
JavaScript arguments, return values, or exception messages are logged by these
new diagnostics. Application-provided logs may still contain sensitive data;
do not enable unrestricted Android DEBUG logging during account login.

Tests: `tests/web-cookies` and `tests/web-javascript`. No live Google account is
required. Successful Google account registration still needs interactive testing.

API references:
- https://webkitgtk.org/reference/webkitgtk/stable/class.NetworkSession.html
- https://webkitgtk.org/reference/webkitgtk/stable/class.CookieManager.html
