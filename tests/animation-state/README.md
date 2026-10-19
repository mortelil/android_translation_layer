# Animation completion state

Build/run this ATL-specific asynchronous probe inside Toolbox:
`sh tests/animation-state/build.sh build-mobile OUTPUT`, then run its APK.
Expected: ANIMATION_STATE_PASS checks=10.

It checks initial/start/end flags, final-transform ordering, cancellation before
dispatch, superseded queued starts, reset versus setStartTime, cancellation from
the start listener, and recursive/repeated cancellation without duplicate ends.

Contract reference: AOSP frameworks/base android-14.0.0_r1,
core/java/android/view/animation/Animation.java, hasStarted/hasEnded, reset,
setStartTime, cancel and getTransformation. Normal completion sets ended before
the callback; cancel notifies before setting ended. setStartTime clears the flags;
reset does not. No AOSP implementation copied in this change.

Important scope: ATL still uses its existing single posted final transformation.
This is state tracking for that dispatcher, NOT a full Android animation clock.
Duration, start offset, repeat/fill semantics and composed animations are not
implemented here. This probe deliberately does not claim Android frame-timing
parity. Animation operations are expected on the UI thread, as in View use.
