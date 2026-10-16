# ImageButton scale regression

Build with the existing widget-theme build script and launch
`org.atl.widgetcolors.ImageScaleActivity` at render scale 1. The red drawable has
24x24 intrinsic pixels inside an 80x80 CENTER_INSIDE button; require a 24x24 red
square. The blue drawable uses FIT_XY in another 80x80 button; require an 80x80
blue square. Both are visually measurable in the screenshot, independently of
Java's getScaleType result.

Verified on x86_64 GTK: red 24x24, blue 80x80. Before ImageButton forwarded
native_setScaleType, both pictures used GTK's default expansion. This tests
CENTER_INSIDE/FIT_XY, not MATRIX or exact CENTER overflow semantics (existing
ImageView backend limitations still apply).
