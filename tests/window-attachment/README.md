# Window attachment and ImageView measurement

Build inside Alpine: `sh tests/window-attachment/build.sh build-mobile OUT`.
Run OUT/attachment.apk via scripts/mobile/run.sh with ATL_BUILD_DIR pointing to
this build and a private ANDROID_APP_DATA_DIR. Require ATTACH_PASS, not exit alone.
Sign and install the same APK on Android for the reference run.

54 ImageView combinations (EXACTLY/AT_MOST/UNSPECIFIED, adjustViewBounds, maxima,
padding, intrinsic aspect ratio) must produce digest -2708459594523278453.
Dialog checks cover deferred parent-before-child attachment, attachment before
measurement, hide/show retaining attachment, removal/reinsertion, child-before-
parent dismissal, and show/dismiss/show without stale removal. Check counts vary
with legitimate platform measurement/traversal counts; digest and invariants do not.

Also run tests/surface-lifecycle for exception propagation during detach and
SurfaceView/TextureView lifecycle, and tests/mobile-smoke for broad regression.
Validated x86_64 ATL and Android API 37. Not a claim of complete window-manager,
configuration-change, multi-display, or ARM support. These are synthetic profiles.

ImageView sizing is adapted from AOSP frameworks/base at
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/widget/ImageView.java;
Apache 2.0 header retained. Attachment references: ViewRootImpl.performTraversals,
ViewGroup.dispatchAttachedToWindow/dispatchDetachedFromWindow, Dialog.dismissDialog.
OMP source commits b1390fae (ImageView portion), 137b29eb, 41edb8f6, e7ea490f,
b1de5d1d; revised integration adds safe child snapshots, deferred-show priority,
correct hide semantics and synchronous removal with asynchronous dismiss listener.
