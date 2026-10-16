# Widget/theme groundwork: drawable states

First milestone: View state selectors must receive enabled/pressed/selected/
activated flags and app-defined states from onCreateDrawableState. A subclass
can reserve extension slots, merge extra states in place, and call
refreshDrawableState to notify its drawableStateChanged override. Stateful
backgrounds receive their initial state as well as subsequent state changes.

Run the normal mobile-smoke build/run against the new ATL runtime.
TestDrawableStates uses a real StateListDrawable with distinct entries to check
initial state, negative enabled matching, custom checked state, selection,
activation, duplicate suppression, and return to default. DrawableContainer
now exposes the selected child and clears it when a selector has no match. It also verifies
merge identity and insufficient-capacity rejection.

Reference: AOSP frameworks/base android-15.0.0_r1,
core/java/android/view/View.java, onCreateDrawableState, mergeDrawableStates,
getDrawableState, refreshDrawableState and drawableStateChanged; also
graphics/java/android/graphics/drawable/DrawableContainer.java, getCurrent and
selectDrawable.
The implementation adapts these contracts to ATL's current state storage;
it does not copy the full Android View implementation.

Remaining scope: GTK focus/hover/window-focus delivery, duplicate parent state,
ViewGroup state aggregation, state propagation to children, foreground/tint
and StateListAnimator updates. Native CompoundButton still uses GtkSwitch and
ignores Android thumb/track drawables. This milestone does not claim Android
switch appearance or resolve every Material TextInputLayout outline mismatch.

CheckedTextView now retains a checkmark drawable, checked state and toggle,
forwards state changes, and renders an explicitly supplied mark at the end of
the text while reserving native content padding. This is deliberately not a
complete CheckedTextView port: XML/theme mark inflation, tint, checkMarkGravity,
RTL changes after attachment and minimum-height sizing still need work.

Validation: mobile-smoke passes, including selected/default/disabled text and
hint colours, Paint/getter agreement, replacement of a selector by an integer
colour, and actual checkmark pixels/bounds. TextView now resolves ColorStateList
against its drawable state rather than always using the default; the integer
setter replaces the selector and getCurrentTextColor no longer returns cyan.
GTK text CSS uses the existing scoped provider lifetime helper.

Build `sh tests/widget-theme/build.sh BUILD_DIR OUTPUT_DIR` inside Toolbox to
create widget-colors.apk. Its rows must render red (#d02030), blue (#2468ac),
and gray (#777777); it also asserts the Java getter/Paint contracts. Use
`python3 tests/widget-theme/check-pixels.py SCREENSHOT.png` on the captured
GTK image (Pillow required). This tests native CSS rendering, not only getters.

The intermittent corrupted AntennaPod text/icon image was reproduced on
unmodified main fe5f64ad; the same experimental widget branch also produced a
clean serial run before further changes. Thus this defect is not declared
fixed or attributed to this milestone. Existing search-result layout and
occasional artwork TLS failures remain. No Nura or Signal validation.
