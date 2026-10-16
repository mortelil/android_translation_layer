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

`TestImageView` checks that bitmap replacement updates drawable identity and
callback ownership, requests measurement, changes the actual native preferred
size, and supports clearing with a null bitmap or resource ID zero.

## XML include visibility

`TestIncludeVisibility` uses compiled layout resources to check visible,
invisible and gone overrides on `<include>`, inherited root visibility, ID
overrides and the absence of a visibility override for `<merge>` roots.
The build requires `aapt2` for these resources (`aapt` is still used to add dex).
Contract reference: AOSP `LayoutInflater.parseInclude`, android-15.0.0_r1,
frameworks/base commit `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`.

## AppOps queries

`TestAppOps` covers `checkOpNoThrow(String,int,String)`: known unsupported
operations are ignored, available storage operations are allowed, location and
microphone follow ATL's permission policy, mismatched UID/package identities are
rejected, and invalid operation names (including null) throw
`IllegalArgumentException`. A controlled permission backend exercises both grant
and denial independently of host configuration and verifies that unsupported or
foreign-app queries do not consult permissions. Repeated queries have no usage
recording side effects.

This is a conservative read-only policy for the context's own app, not Android's
AppOpsService. No persisted user overrides, cross-package queries, foreground
modes, watchers, usage accounting, or start/finish operations are implemented.
Known operations without an ATL backend (including usage statistics, overlays,
and picture-in-picture) return `MODE_IGNORED`; no permissive `MODE_DEFAULT`
fallback is used. Existing `permissionToOp` and proxy-note stubs are unchanged
and are not covered by this implementation. In particular, this is not an
AppOps security boundary or a claim of full Android permission compatibility.

Operation vocabulary and mode values are derived from AOSP
`frameworks/base/core/java/android/app/AppOpsManager.java`, commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` (Android 17), under Apache-2.0.
The local policy is ATL-specific. AOSP's `strOpToOp` validates names even for
NoThrow queries; NoThrow does not mean invalid arguments are silently accepted.

`TestCompatRepairs` covers AES default/explicit sizes and CBC key generation
without persisting keys, all three null-parameter GCM initialization variants
with authenticated round trips and tamper rejection, TextureView ownership on
all detach paths and surface replacement, binary DNS-SD TXT decoding, and a
barrier check proving a backend event can enqueue while the Java operation
lock is held. The separate `tests/nsd-probe` exercises real Avahi callbacks
through 20 browse/resolve/stop cycles and rejects simultaneous listener reuse.

For keystore backend-failure testing, run `tests/keystore-probe` against a
working Secret Service using a disposable application ID/profile, then run the
same probe with `ATL_TEST_KEYSTORE_UNAVAILABLE=1` and an unreachable test bus.
The latter must report a bounded read error and must not write any key. Restore
the original bus and rerun with the same profile: persistence decryption and
key identity must still pass. Never point this test at a personal app's schema.

Reference contracts for the review repairs: AOSP `android-16.0.0_r1`,
`keystore/java/android/security/keystore2/AndroidKeyStoreKeyGeneratorSpi.java`
(default AES size) and `core/java/android/view/TextureView.java`
(surface ownership/replacement). The implementation remains software-only;
these tests do not claim hardware key-policy enforcement or a TextureView
compositor. Original lane reports under other test directories describe their
recorded pre-review snapshots, not validation of every later revision.

## Bitmap density

`TestBitmapDensity` covers resource density propagation, mdpi/xhdpi selection,
`nodpi`, actual decode resampling, `inScaled=false`, the `inScreenDensity`
exemption, bounds-only decoding, density-preserving copies and parcels,
BitmapDrawable intrinsic sizing, GTK ImageView preferred sizing, and pixels
rendered into explicit bounds. Synthetic 24px mdpi/red, 48px xhdpi/blue and
24px nodpi/green PNGs make resource selection independently observable.
Run the suite with `ATL_RENDER_SCALE=1`, `1.5`, and `2`; a 24dp resource must
occupy 24, 36 and 48 Android pixels respectively, then the window's inverse
transform preserves its logical size.

Contract references: AOSP frameworks/base `android-17.0.0_r1`, commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`, graphics/java/android/graphics/
`Bitmap.java` (`scaleFromDensity`), `BitmapFactory.java`
(`decodeResourceStream`, `setDensityFromOptions`), drawable/`BitmapDrawable.java`,
and libs/hwui/jni/`BitmapFactory.cpp` (density resampling and bounds reporting).
The existing Apache-2.0 header on BitmapFactory is retained; GTK drawing and
resource integration are ATL-specific. This does not implement nine-patch
chunks, all decode options, bitmap tiling/gravity, or full Canvas density support.

`DensityActivity` is a separate, non-exiting synthetic screen in the same APK.
Launch it with `-l DensityActivity` and a fixed logical window size (for example
`-w 421 -h 924`) at each render scale. Compare text, compound drawables, EditText,
CheckBox, Switch and SeekBar, then open the popup and tap its body to dismiss.
The popup is explicitly 240x80 dp: its visible bounds and click response must
stay the same at 1x and 2x, and opening it must not resize the main display.
The dialog button is an additional manual check, not an automated assertion.

The 2026-10-16 comparison found equal 240x80 logical popup bounds at (21,413)
and successful body clicks at 1x and 2x after the PopupWindow repair. Dispensa's
native dropdown arrow and Android popup rows also retain their logical sizes;
selection changes the product category. The full smoke suite passes at 2x.
The extended screen now keeps native check/radio glyphs, Switch dimensions,
SeekBar geometry and compound icons at the same logical size. Text setters in
CompoundButton/CheckBox/RadioButton now reach TextView styling. PopupWindow uses
a real Android container so a standalone TextView is measured and laid out,
without replacing the content's own background. TestPopupContracts checks this.

Additional pixel contracts in TestBitmapDensity cover Canvas position-based
bitmap density (explicit rectangles and matrices must not apply density), vector
constant-state copies at another target density, and XML nine-patch tint and
fixed stroke thickness. The nine-patch pixel probe uses ATL's GSK background
rendering path; it does not claim plain bitmap-Canvas support for all drawables.
Run at 1, 1.5, 2 and 3. The generated nine-patch fixture has a two-dp solid stroke.

EditText's default constructors select editTextStyle, not textViewStyle. Existing
AOSP edit_text_material.xml supplies the underline through its selector and
nine-patch resources. XML nine-patch src/tint are now loaded; fixed segments scale
with resource density while stretchable segments consume the remaining space.
The test also checks that callers can explicitly remove EditText's background.

Known scope limits: native widgets still use GTK shapes/colors rather than
complete Android theme parity. WindowManagerImpl auxiliary windows and native
Spinner list surfaces need dedicated coverage. Arbitrary nine-patch options,
stateful tint lists, all compound-drawable arrangements, dynamic monitor-density
changes and plain bitmap-Canvas drawing of native paintables are not fully covered.
The Rhythm welcome-screen title has equal text size but different vertical
placement at 1x and 2x; this remains a layout issue to investigate, not a passed
pixel-parity result. Signal and Nura were not tested in this batch.
