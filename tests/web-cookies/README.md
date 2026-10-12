# WebView cookie integration

Build with `sh tests/web-cookies/build.sh BUILD OUT`. Start
`python3 tests/web-cookies/server.py` on the same host/network namespace, then
run inside the configured Alpine environment:

```
sh tests/web-cookies/run.sh BUILD OUT/web-cookies.apk RUN_OUTPUT
```

Only synthetic cookies and localhost HTTP are used. Each run gets a private
profile under RUN_OUTPUT. The test checks an HTTP Set-Cookie response (including
HttpOnly), JavaScript writes, Java writes sent on a subsequent HTTP request,
HttpOnly exclusion from JavaScript, HTTPS/domain isolation, cookie reads on the
JavaScript bridge worker, session deletion retaining persistent cookies, all-cookie
deletion and callback delivery on the main Looper. It fails against the old
separate-store implementation at the HTTP-to-Java check.

This does not prove Google login, TLS account exchange, persistence across abrupt
process termination, third-party-cookie policy or full Chromium compatibility.
