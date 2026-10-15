# View state compatibility repair

Enabling real hierarchy-state dispatch exposed a missing prerequisite:
`View.onSaveInstanceState()` returned null. AndroidX Toolbar constructs its
saved-state wrapper around the superclass result and rejects null. In AntennaPod
this crashed a normal bottom-tab switch while FragmentManager saved the old view.

`AbsSavedState` is copied unchanged from AOSP frameworks/base,
`android-8.1.0_r1` (commit `250714c67ba9d2029bc88284672949a87b243b9a`), `core/java/android/view/AbsSavedState.java`, with the Apache-2.0
header retained. `View.BaseSavedState` is taken from the same revision of
`core/java/android/view/View.java`; its data-carrier fields and parcel format are
retained. The base view returns EMPTY_STATE. This does not add Android autofill.

The associated Parcelable.ClassLoaderCreator method and loader-aware read path
allow these wrappers to round-trip. The existing text-only TextView saved state
now has its own CREATOR and writes its superclass state. It still does not
promise full TextView state (styled text, selection, editor/IME state).

The mobile smoke test models a subclass wrapping the base view state, nested
Parcelable state, EMPTY_STATE, null superclass rejection, class-loader delivery
and text state round-trip. This is a prerequisite repair, not a claim of full
view restoration or persistence after coordinator death.

Hierarchy dispatch also respects `setSaveEnabled` and the parent-traversal flag.
`dispatchFreezeSelfOnly`/`dispatchThawSelfOnly` let RecyclerView save its own
adapter state without traversing/replaying recycled children. Both traversal
modes and disabled saving are covered by the same smoke test.
