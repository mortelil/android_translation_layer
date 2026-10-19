# Cookie flush durability boundary

Build in atl_dev: `sh tests/cookie-flush/build.sh build-mobile OUTPUT`.
With ATL_PREFIX/ATL_CORE_JAR configured, run:
`sh tests/cookie-flush/run.sh build-mobile OUTPUT/probe.apk FRESH_RUN_DIRECTORY`.
Uses only a private profile and synthetic.invalid, with no HTTP requests.

The first process writes persistent cookies from main and worker threads, calls
flush, writes a readiness marker, and waits for SIGKILL. The restarted process
checks both persistent cookies survived, the session cookie did not, and deletion
followed by flush works. A deliberately invalid database path must throw, not
report success; the synthetic test database is restored in finally. The latter
fault injection is ATL-specific and enabled only by the test runner.

Implementation relies on the WebKitGTK Soup backend (tested 2.48.1/libsoup 3.6.6):
WebCookieManager::getAllCookies serializes with network-process cookie operations;
NetworkStorageSession::setCookie uses SoupCookieJar synchronously. SoupCookieJarDB
commits in its changed handler but sets SQLite synchronous=OFF. After the IPC
barrier, ATL fsyncs the existing database, optional WAL and containing directory.
No second cookie store or schema manipulation is introduced. Only persistent
cookies are retained, matching WebKit's session policy.

References:
- WebKit commit 267c753847aa9c2861fae79d6334f9441aeef8bb (webkitgtk-2.48.1),
  Source/WebKit/NetworkProcess/Cookies/WebCookieManager.cpp and
  Source/WebCore/platform/network/soup/NetworkStorageSessionSoup.cpp.
- libsoup soup-cookie-jar-db.c: soup_cookie_jar_db_changed and open_db.

Limits: tests exercise abrupt process termination, not power loss. Backend
changes to deferred writes require rechecking this barrier. WebKit/libsoup do not
surface earlier SQLite mutation errors through get-all; this does not repair
that upstream limitation. Cookies still in an outstanding HTTP request are not
included. No account data or live credentials are used.
