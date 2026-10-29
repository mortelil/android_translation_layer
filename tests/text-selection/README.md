# Text selection

Build in atl_dev with `sh tests/text-selection/build.sh BUILD_DIR OUTPUT_DIR`.
Run probe.apk with the candidate ATL runtime; require TEXT_SELECTION PASS.

Checks visible single-line geometry, reversed and collapsed selections, path
replacement, UTF-16 supplementary characters, RTL runs, multiline extents,
highlight-before-text order, nullable highlight, vertical offset and canvas
state restoration (including raster pixels).

Semantics reference AOSP frameworks/base core/java/android/text/Layout.java,
android-15.0.0_r1, getSelection/getSelectionPath and drawHighlights. No source
copied. Pango supplies shaped visual ranges instead of assuming that logical
character order is visual order. This inherits existing ATL Layout limitations
(e.g. paragraph alignment/margins and include-font-padding), not full AOSP parity.

Firefox 157 integration: open a public page, click the address field, verify
visible selection and replace the URL. WindowManager subwindows must display
app-provided selection handles without GTK's added tooltip border/arrow/shadow.

Visual subwindow probe: launch the same APK with
`-l org.atl.textselection.HandlesActivity`. A magenta marker should have no
extra border, arrow, padding or shadow. The probe moves the subwindow and then
removes it after 30 seconds; require HANDLES REMOVE PASS. Capture the popup
surface itself (X11 window capture of the parent excludes popovers).
