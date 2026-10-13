# EGL presentation contracts

Run inside Alpine: `sh tests/egl-readback/run.sh OUTPUT_DIR`.
Requires Mesa with surfaceless EGL and OpenGL ES 3. Tests actual RGBA pixels,
pack alignment/row/skips/PBO restoration, read-buffer restoration, preservation
of an application GL error across an intervening context, invalid dimensions
and an incomplete framebuffer. A separate test verifies dynamic function lookup
selects ATL adapters without introducing a second libEGL shim. That test's
adapter is explicitly a routing fixture, not a renderer.

Integration also replayed OMP's Sketchbook fixtures/probe-egl application via
Java EGL10/SurfaceView. A slower-color variant showed blue -> red -> blue in
captured app pixels, with successful swaps and GL_NO_ERROR. Native lifecycle
tests in tests/egl-lifecycle also pass. These observations do not establish
video decoding or any third-party app's full functionality.

Presentation adapts the OMP Sketchbook/VLC CPU readback implementation (f81a24b,
a74d777). Repairs retain producer buffers until GTK consumes them, bounding
queued frames, preserve pixel-pack state and application errors, return failure
when readback fails, and own the pending frame callback's queue reference.
This favors compatibility over zero-copy performance. The redundant native
libEGL shim and pixel-dump diagnostics are not included; the normal Bionic
loader already prefers bionic_ adapters, and eglGetProcAddress now does too.

`native/build.sh BUILD_DIR OUTPUT_DIR` creates a JNI probe for ATL on Alpine
(x86_64/aarch64). Sign its APK with a local test key and require
`EGL_NATIVE_DISPATCH PASS`. The native module is loaded by the real Bionic path;
it checks dlsym/libEGL and eglGetProcAddress against the actual ATL adapters and
calls eglGetError without recursive forwarding. This is an Alpine-built loader
probe, not an Android-NDK ABI certification. Tested on x86_64.

The existing mobile-smoke suite passes with JAVAC explicitly set to the Java 8
compiler (using JDK21 with only -target 8 emits incompatible ByteBuffer calls).
