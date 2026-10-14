# Native bitmap unlock regression

Run `sh tests/bitmap-unlock/build.sh BUILD_DIR OUTPUT_DIR` inside the Alpine
ATL build environment, then sign `OUTPUT_DIR/probe.apk` with a test key and run
it against that same ATL build in an empty app-data directory.

This is an ATL native integration test: its JNI library links against the
build's libandroid and the container's libc. It is not an Android-device APK.
The library calls the exported AndroidBitmap getInfo/lockPixels/unlockPixels
functions; it does not call the Java implementation helper directly.

Success is `BITMAP_UNLOCK PASS checks=29` plus `files/results.json` with PASS.
Three successive writes check every pixel, row stride, dimensions, and that
unlock leaves the bitmap usable. Explicit recycle remains idempotent and marks
it recycled. An assertion or missing success marker is a failure.

Review verification: unchanged main 281098dc fails check 1 (isRecycled after
unlock); the same signed APK passes all 29 checks with the fix. This proves the
bitmap lifetime/pixel contract, not OsmAnd's complete map rendering.
