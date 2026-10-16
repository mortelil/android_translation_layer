# Popup placement regression tests

Run inside the Alpine development container from the ATL repository:

```sh
sh tests/popup-placement/run.sh /tmp/atl-popup-test
```

Requires a C compiler, GTK 4 development headers, JNI headers and Xvfb.
The geometry tests exercise dropdown left/right alignment, offsets, overlap,
above-anchor fallback, clipping and absolute top/bottom/center placement.
The native test compiles the actual JNI implementation and inspects GdkPopup
surface coordinates, including a zero-sized GTK anchor with nonzero Android
View dimensions and reparenting the same popup.

`tests/mobile-smoke/TestPopupContracts.java` checks decor policy, null content
and compound padding in both text directions. `TestSpinner.java` checks native
selection synchronization, item IDs and listener removal. `TestCollapsibleActionView`
checks that app classes implementing the framework interface load and dispatch.

For pointer routing, build `tests/spinner-ui/build.sh BUILD OUTPUT` and launch
OUTPUT/spinner.apk. Click the upper spinner, choose Choice 2, then click the
lower spinner. Expect one native menu for the upper control, a closed menu
and Choice 2 after selection, `SPINNER_SELECTED 2 id=102` in output, and one
`SPINNER_CUSTOM_CLICK` with no menu for the lower control. This also checks
that clicking native popup rows is not intercepted as a new spinner click.

Integration checks: AntennaPod 3.12.0 (3120295), More -> Add podcast -> search;
Dispensa 24, add product -> storage dropdown -> Fridge. Search additionally
requires the host AsynchronousCloseMonitor fix in art_standalone; otherwise
musl rejects signal 34 registration and the process can die during network I/O.

Scope: this is not a complete PopupWindow implementation. Keyboard/inset
policy, focus/outside-touch semantics, negative layout sizes, automatic anchor
tracking and all update overloads are not covered. GTK still constrains native
popups to the screen. Spinner tests do not cover adapter replacement during a
selection callback, accessibility, keyboard selection or touch-drag selection.
