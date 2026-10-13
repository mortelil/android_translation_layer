# Text and button style contracts

Build Java/dex in Alpine with CLASSES_ONLY=1 sh tests/text-style/build.sh BUILD
OUTPUT, then package manifest/res/classes.dex with host Android SDK aapt and
sign, as documented by tests/window-geometry. Launch and require TEXT_STYLE
PASS. The same APK passes Android API 37 and ATL at render scale 2.625.

Checks default textViewStyle (24sp), buttonStyle (28sp), explicit defStyleRes
(32sp), and the direct Button.setTextSize(float) API (36sp). XML sizes use
Android's getDimensionPixelSize rounding; direct float setters retain the
fraction. The visual screen must show four increasing text sizes; inspected
on ATL to verify that the native button label changed too, not just Paint.

References: AOSP frameworks/base android-15.0.0_r1 TextView.java constructors,
TextAppearanceAttributes/applyTextAppearance; Button.java constructors. ATL
keeps its native widgets; this does not import full TextView/AlertController.
AlertDialog content now inherits its actual context instead of an unrelated
system context with no theme. Explicit dialog-theme resolution remains an
existing limitation; this patch does not claim to implement it.
