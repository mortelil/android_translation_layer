# Unconfigured caption locale

Build in Toolbox: `sh tests/captioning-locale/build.sh build-mobile OUTPUT`.
Run `org.atl.captioninglocale/.Probe` on ATL and on an Android emulator whose
`accessibility_captioning_locale` secure setting is unset. Expected:
`CAPTION_LOCALE_PASS checks=3`.

Checks service lookup, unset locale, and independence from Java's default locale.
The latter is changed only within the probe process and restored in finally.
This covers the missing method used when ReVanced opens a video, not playback.

ATL currently exposes fixed, disabled caption preferences; it has no caption
language configuration backend. Returning null represents an unset preference,
not an enabled caption implementation. No host locale is substituted.
Reference: AOSP frameworks/base android-14.0.0_r1,
core/java/android/view/accessibility/CaptioningManager.java#getLocale.
No AOSP implementation copied.
