# OnPreDraw cancellation and frame retention

This probe checks callbacks and actual presentation. It first withholds a magenta
frame, allows it, mutates listener registration during dispatch, then cancels a
green replacement. The visible magenta frame must remain until green is allowed.
The final listener remains registered during window teardown.

Build inside the Alpine ATL environment:

    sh tests/on-pre-draw/build.sh BUILD_DIR OUTPUT_DIR

Sign OUTPUT_DIR/on-pre-draw.apk with a test key on the host. Use an empty profile
and the test runner's UI mode. The review recipe was:

    shot:initial;wait:10;shot:allowed;wait:5;shot:retained;wait:8;shot:updated;close:

Capture stages relative to launch. Slow dex compilation can shift these windows;
inspect the application's result JSON and captures, do not count a runner exit
alone as success. Analyze with Pillow on the host:

    python3 tests/on-pre-draw/analyze.py CAPTURE_DIR APP_DATA/files/results.json

The Java result must be PASS. The pixel checks require no initial content,
magenta in the first allowed frame, identical magenta coverage during cancelled
replacement, then green with no magenta after acceptance. Listener checks cover
self-removal, adding/removing during dispatch, last-listener removal and
re-registration. Close the window while the final listener is still registered.

Review control: OMP's original 021a72d passed callback checks but changed 459800
magenta pixels to zero during cancellation. With frame retention the same signed
APK and capture recipe preserve all 459800 pixels, then replace them with green.
This is an X11/cairo test; Wayland and hardware renderers are not claimed tested.

Implementation: ViewTreeObserver is owned by the GtkWindow. Pre-draw dispatch
runs at the root snapshot boundary. A cancelled traversal queues a future frame
and reuses the previous immutable GskRenderNode. Allowed draws replace it;
window disposal releases it. A local JNI reference protects the observer during
listener removal in a callback. This is an ATL adaptation of AOSP
ViewRootImpl.performTraversals()/ViewTreeObserver behavior, reference
frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d (android-17.0.0_r1).
