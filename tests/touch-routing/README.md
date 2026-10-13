# Java touch routing and GTK coexistence

Build in Alpine with `sh tests/touch-routing/build.sh BUILD_DIR OUTPUT_DIR` and
sign the APK. Launch org.atl.touchrouting.Probe; require TOUCH_ROUTING PASS and
files/results.json. Same probe passes on ATL and Android API 37. It checks
reverse child order, child-local/scroll-adjusted coordinates, retaining a target
outside its bounds, rejecting a DOWN, interception/CANCEL, disallow-intercept,
click cancellation and offsetting every pointer. Its isolated click view runs
posted actions immediately so that the comparison tests event logic without
requiring an attached window; the real UI test below covers queued clicks.

Launch org.atl.touchrouting.Visual for actual input. Click the native button at
left and the blue region at right once each. Require the visible counter and
log to read `Native=1 Covered=1`. The right-hand region has a SurfaceView over
an accepting sibling, and TOUCH_WINDOW action=0/1 proves the native window
fallback reached Activity dispatch. The left button retains the GTK path.
Verified with render scale unset and 2.625. Do not count screenshots alone as
input success. The window's title-bar offset must be included in click positions.

Replaces the OMP VLC a74d777 global target/untransformed child-hit-test approach.
Reference: AOSP frameworks/base android-15.0.0_r1, ViewGroup.dispatchTouchEvent's
non-splitting path, View.onTouchEvent and Activity.dispatchTouchEvent. This is
an ATL adaptation, not a wholesale import of AOSP input dispatch.

Scope: Java unsplit gesture ownership plus a per-window GTK fallback, without
stealing DOWN events rejected by the Java hierarchy or existing native input
widgets/controllers. Window replacement/unmap cancels owned gestures; JNI and
widget references are released. Pointer identities in the new fallback are
mapped from opaque GDK sequences rather than using pointers as array indexes.
Existing GTK wrapper-controller routing remains in place.

Limitations: splitting different fingers across different children, arbitrary
view matrices/Z-order overrides, long-press/pressed-state delays and full Android
MotionEvent metadata are not implemented by this patch. Native multi-touch
sequence generation still needs a real touchscreen replay; the focused probe
checks multi-pointer coordinate offsets, not touchscreen hardware. The existing
setMotionEventSplittingEnabled stub is not upgraded or claimed as supported.
