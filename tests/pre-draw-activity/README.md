# Pre-draw ownership across activities

Regression for PR #53 / AntennaPod 3.12.2: its splash activity registers an
always-false pre-draw listener, launches MainActivity, then finishes. Android
keeps observers per view hierarchy. ATL uses one GtkWindow with a GtkStack;
storing the observer on that window let the old splash veto the new activity.

This probe uses two real activities, without app-specific code or networking:

1. First activity cancels every draw, but must receive pre-draw callbacks.
2. Second activity initially cancels its own draws. First must stop receiving
   callbacks while hidden, and second must have its own registration.
3. Remove and re-add the *hidden* activity's listener. This must not clear or
   replace the second activity's registration. Allow second to draw green.
4. Finish second and allow first to draw magenta. First's retained observer
   must work again without re-registration on resume.
5. Print `PREDRAW_ACTIVITY PASS` with nonzero callback and draw counts, or FAIL.
   Absence of the terminal result is not a pass. A 10-second watchdog reports
   missing second activity / return drawing.

Build inside the Alpine toolbox:

```sh
sh tests/pre-draw-activity/build.sh /absolute/build /absolute/output
```

Launch the resulting `pre-draw-activity.apk` with the runtime being tested.
The build produces an **unsigned** APK; sign with a development key before
installing on an Android emulator/device. There are no Java lambdas requiring
an additional desugaring step. Allow startup/dex compilation plus 10 seconds.

Run `tests/on-pre-draw` as well: this new probe does not replace its checks for
listener mutation during dispatch and retaining the last presented frame when
a later frame is cancelled. Compare AntennaPod with a fresh test profile and
verify navigation between Home, Subscriptions and More, not merely process startup.

Reference contract: AOSP `ViewRootImpl.performTraversals()` dispatches
`mAttachInfo.mTreeObserver.dispatchOnPreDraw()` for that hierarchy and reschedules
cancelled draws. Reference revision: android-17.0.0_r1,
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d. No AOSP code was copied for this patch.

Scope: this fixes pre-draw ownership only. Global-layout listener ownership,
full multi-window lifecycle, and transitions between activities are separate
areas; this probe is not a complete lifecycle conformance suite.

Validation (2026-10-14, Alpine x86_64 / GTK Xvfb):
- Before fix (PR #53 merge f0b8c5cb): FAIL `hidden first observer dispatched`,
  second callback/draw counts both zero.
- Fixed: PASS, first/second callbacks 28/31, actual onDraw counts 1/1.
- Existing on-pre-draw probe: PASS; captured pixels black before the initial
  allowed draw, magenta afterwards, still magenta during cancelled replacement,
  then green after replacement was allowed.
- Existing windowid-probe: `RESULT failures=0`, including moving a view between
  activity and dialog windows and detached-view identity.
- AntennaPod 3.12.2: Home, Subscriptions and More render and respond to clicks.
  Search, playback, ARM/Nura and Android-reference execution were not tested
  for this patch. No claim of complete AntennaPod support.
