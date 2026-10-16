# GSK rectangle paint styles

Run in the ATL build container:

```
sh tests/rectangle-style/run.sh /tmp/atl-rectangle-style
```

This compiles the production GskCanvas native implementation and renders its
nodes to pixels with GSK's Cairo renderer. For rectangles and rounded rectangles,
it checks FILL, STROKE and
FILL_AND_STROKE: empty/filled centres, a width-4 stroke centred on the boundary,
outside transparency, and 50% alpha applied once in the fill/stroke overlap.
The pre-fix implementation fails the rectangle STROKE centre assertion and
the rounded-rectangle outer stroke assertion.

Contract reference: AOSP `android-17.0.0_r1`,
`frameworks/base/graphics/java/android/graphics/Canvas.java` (`drawRect`) and
`Paint.java` (`Style`). No AOSP code copied.

The change preserves existing shader and colour-filter handling by painting
through one union mask. This test exercises flat colours, not Java shader
parameter extraction. It does not fix zero-width hairlines under scaling, unsupported shaders or transfer modes,
or missing widget backgrounds higher in the view system. Passing it does not
establish complete app visual parity.
