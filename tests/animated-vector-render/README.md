# Animated vector rendering probe

Inflates a real vector and path-data animator from compiled Android resources.
Checks visible initial pixels, the final morphed path, one start/end callback
pair, no duplicate start, stopped state, independent constant-state clone,
retained evaluator in the clone, stop-at-end, reverse-to-start, and callback removal.
An app-defined drawable wrapper is displayed inside a parent overriding
`drawChild`. The test also requires that GTK presents the completed animation
without a window resize; bitmap-only checks would miss a stale parent node.
Start on the first Choreographer frame: cold app startup before the first frame
must not count against the 100 ms animation duration.

Build/package as described in ../transition-drawable/README.md. The expected
marker is `ANIMATED_VECTOR PASS` and process exit 0.

AnimatedVectorDrawable uses the Java implementation from AOSP
`android-5.1.1_r38`, adapted to ATL's VectorDrawable and AnimatorSet. This does
not add Android's native RenderThread animator backend or complete every
AnimatorInflater/keyframe feature. Path parsing is the existing AOSP parser
moved out of VectorDrawable so animation and drawing share its node type.

AOSP frameworks/base source commit:
`6e9224c3ef7a2b0064b80e9a597ae9316f80d004` (Apache-2.0).
