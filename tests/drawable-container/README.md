# Selected paintable ownership

Run inside Alpine Toolbox:

    sh tests/drawable-container/run.sh /tmp/atl-drawable-container

This compiles the actual native DrawableContainer implementation. It checks
that the selected GdkPaintable stays alive after its caller releases it,
forwards content/size invalidation, disconnects retired children, clears an
empty selection, and releases the final child on container disposal.
The unpatched implementation fails the first lifetime assertion.

This proves a native ownership bug, not that it explains every observed app
rendering artifact. In particular, AntennaPod's prior corrupted-text screenshot
was not reproduced when tested alone on the unchanged widget-state candidate.
