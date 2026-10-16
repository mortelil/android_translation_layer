# Bitmap renderer transfer

Run in the development container under a display, for example:

```
xvfb-run -a sh tests/bitmap-renderer-transfer/run.sh /tmp/bitmap-transfer
```

The test creates 64 colored/translucent glyph bitmaps through the production
Bitmap JNI path, releases each temporary renderer, then composites the retained
textures on a different renderer/display. It compares all pixel channels with
direct rendering. No per-image readback is performed by the test before composition.

This is a contract/regression test, **not a deterministic reproduction** of the
intermittent corruption: the old implementation also passes on the tested Xvfb
configuration. The motivating integration evidence is Dispensa's product form:
repeated baseline runs produced corrupted/random icons, while two candidate runs
showed the back arrow, cutlery, quantity buttons and calendar correctly.

Materializing GPU output incurs readback/copy cost when a Bitmap snapshot becomes
a texture. CPU textures are retained without an extra copy. Normal RenderNode
and window drawing do not take this path. ARM/Nura performance remains unverified.
