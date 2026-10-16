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

Initial validation: mobile-smoke passes, Dispensa product text entry and Fridge
selection complete without the new missing-getCheckMarkDrawable crash, and
AntennaPod search returns a result. App screenshots still contain graphical
artifacts; visual regression clearance has NOT been granted for this branch.
