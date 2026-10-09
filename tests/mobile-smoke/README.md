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
It sends only synthetic values, never a real account or login request.

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
