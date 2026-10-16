# Retained RenderNode updates

The former native tree-patching test has been replaced by
`tests/mobile-smoke/TestRetainedDisplayList.java`. It exercises the actual Java/JNI
record/replay path and checks rendered pixels, including initially empty children,
rounded path clipping, paint capture, child re-recording/discard, transitive updates,
cached unchanged nodes, group opacity, transforms and cycle rejection.

Run the mobile-smoke suite in the configured ATL build environment. The test uses
synthetic bitmaps, not app profiles.

The old implementation inserted zero-area placeholders into a GTK snapshot and
replaced them after snapshot optimization. GTK could remove those placeholders,
making reconstruction impossible. RecordingCanvas now retains child references
and drawing state until replay, before GTK optimizes the complete content.

Design reference: AOSP android-17.0.0_r1, frameworks/base/libs/hwui/pipeline/skia/
SkiaDisplayList.h and RenderNodeDrawable.cpp (commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d). This is an original GTK implementation;
no AOSP implementation was copied. Existing Canvas limitations (such as layer
color filters and unsupported 3D transforms) are outside this change.
