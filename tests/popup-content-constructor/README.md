# PopupWindow content constructor

Build inside Alpine: `sh tests/popup-content-constructor/build.sh build-mobile OUT`.
Run OUT/probe.apk in a private ATL profile; sign/install the identical APK on Android.
Expected: `POPUP_CONTENT_PASS checks=7` (tag PopupContentProbe).

Checks cover retained/null content, initial zero dimensions, no implicit display,
explicit dimensions, and retaining MATCH_PARENT/WRAP_CONTENT values in getters.
The latter are requested layout metadata, not a measurement of GTK's natural size.
This patch does not implement the existing missing automatic layout behavior for
negative sizes or focus policy. It addresses construction and dimension metadata,
not the separate video rendering, audio distortion or TLS errors in ReVanced.

Reference: AOSP android-15.0.0_r1 PopupWindow(View), getWidth/getHeight:
https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/widget/PopupWindow.java
