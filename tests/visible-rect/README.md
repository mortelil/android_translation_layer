# Visible rectangles

View.getLocalVisibleRect previously returned false unconditionally. Litho uses
this query to skip mounting off-screen content, including YouTube results.
The implementation now follows the Java view hierarchy and AOSP's origin,
scroll and ancestor-clipping rules, rather than returning GTK widget bounds.
Global offsets describe the unclipped origin, not the clipped rectangle.
getLocationOnScreen retains its native unclipped position query.

Reference: AOSP frameworks/base android-14.0.0_r1,
299fe6f5d6fc6f1af7c3411dcf4e5efdf7217368, View.java
getGlobalVisibleRect/getLocalVisibleRect and ViewGroup.java getChildVisibleRect.
Apache-2.0 headers retained/added for the adapted code.

Build inside Alpine: `sh tests/visible-rect/build.sh BUILD OUT`.
Sign OUT/probe.apk, then run the same APK with the candidate ATL runtime in a
private profile and on Android (`org.atl.visiblerect/.Probe`). Require
VISIBLE_RECT_PASS checks=23, no FAIL. The cases cover ancestor and padding
clipping, clipping flags, parent and own scroll, global origin, fully clipped
and zero-size views, a detached view, and a real attached window/child. Android API37 and ATL pass.
Also run the existing global-layout probe for attached views and dialogs.

Scope: rectangular visibility queries in the existing single-window hierarchy.
This does not implement occlusion by sibling windows, clipBounds rendering,
shape clipping or missing View property-to-matrix support (getMatrix still
returns identity in ATL unless overridden). Matrix mapping uses getMatrix as
AOSP does; this patch does not claim that all transforms render correctly.
Stored clipChildren/clipToPadding flags are honored by visibility queries;
this change does not add native rendering clipping to their existing setters.
