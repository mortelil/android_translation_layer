# Child display-list updates through effects

Run inside atl_dev:

```
sh tests/render-node-updates/run.sh /tmp/atl-node-updates
```

The test compiles the production RenderNode implementation. It replaces a child
beneath opacity, rounded clipping, a colour matrix, a fill path, a stroked path,
and both branches of an alpha mask. Pixel output must match a freshly built
expected tree, including the replacement's different alpha. Unrelated child
updates preserve parent identity. The original implementation fails the first
replacement assertion.

Rhythm's setup filter cards exposed the mask case: their backgrounds appeared,
but child text and controls stayed blank. After this change the controls render.
This is a GSK display-list integration fix, not an app-specific workaround.
Other GSK wrapper types (e.g. blur, blend, repeat and custom shader nodes) are
not covered here; add preserved-parameter and pixel tests when supporting them.
