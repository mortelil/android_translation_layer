# Android API smoke tests

Run `sh scripts/mobile/test.sh` inside Alpine after building the fork and its
locked companion dependencies. The runner compiles a small Android APK, installs
it in a dedicated test directory, generates synthetic media fixtures, and checks
both process status and `ATL TEST APK PASSED`. No private library is needed.
`ATL_CORE_JAR` optionally selects the experimental D8 core runtime; see
`doc/MobileFork.md`. The same bytecode APK runs on x86_64 and ARM64.

## Text editing and rendering

`TestEditText` exercises the three-argument constructor used by AndroidX, live
Editable writes, native GTK changes, UTF-16 change ranges (including emoji),
before/on/after notification order, listener removal, cursor/selection, rejected
InputFilters, internal watcher isolation and password type/transformation state.
The same checks run on a subclass whose `getText()` delegates to
`super.getEditableText()`, matching AppCompat's pre-28 compatibility path. A
re-entry guard catches incorrect virtual redispatch without overflowing the test
VM's stack, and both accessors must return the same live buffer. See
[AOSP TextView (Android 8.1)](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/java/android/widget/TextView.java)
and [AndroidX AppCompatEditText](https://github.com/androidx/androidx/blob/androidx-main/appcompat/appcompat/src/main/java/androidx/appcompat/widget/AppCompatEditText.java).
It sends only synthetic values, never a real account or login request.

`TestTextSize` checks SP/DP/PX conversion with distinct density and font scale,
fractional sizes, synchronization with the measurement paint, and GTK label
width against Android text measurement after replacing text. It covers labels
and editable fields. Default line height must match the paint font metrics and
change with font size; custom line spacing remains unsupported. XML dimensions are already pixels and must not be scaled
again. The same test checks the pre-layout measurement phase: no fabricated
layout before measurement, content width excluding padding, wrapped line count,
and refresh after changing text and measuring again. A footer-space test changes
padding after measurement, repeats the same measure specs, and checks both the
expanded native size and restoration after clearing padding. Unchanged padding
must not request another layout. This does not establish complete TextView layout, autosizing or native
button styling support.

`TestQwerty` covers full-keyboard insertion, selected replacement and code-point
delete. `TestTextSpans` checks UTF-8 attribute offsets, Pango measurements, rendered
foreground pixels and dynamic span addition/removal. `TestDynamicLayout` also
covers transformed display text and fallback line metrics.

These do not establish full IME/Android editor compatibility. Follow-up tests
should cover preedit cancellation across focus changes, accessibility, complex
scripts and grapheme clusters, repeated field creation/destruction, native GTK
password visibility, and style spans in editable/custom-Canvas text. The Nura
Plexamp experiment separately checks native password masking and repeated GTK
insert/delete against the real React Native fields without submitting a form.

## Other regression coverage

The APK checks bitmap transfers, Canvas clip/transform scope and actual inverse
clip pixels, cookies and callback threading, network state/policy, HardwareBuffer
ownership, unavailable NFC types, content-trigger job rejection, observer lifetime,
media stream access and synthetic document grants. Native audio/buffer tests live
in `tests/native-compat`; their ALSA input is the null test device, not a microphone.

API tests complement app testing. They do not verify accounts, server uploads,
Signal messaging/calls, LocalSend peer transfers or Plexamp playback.
