# TypedValue dimension contracts

AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/util/TypedValue.java; Apache-2.0 header retained.
Initial candidate: OMP OsmAnd lane 578ff85. Both conversion aliases are included.
ATL uses linear DisplayMetrics.scaledDensity in both directions. Non-linear
FontScaleConverter is not exposed or claimed by this patch.

The default probe covers every unit, positive/negative/zero values, inverse
conversion and aliases, zero metrics and invalid units (80 checks). Run the
same APK on Android API 37 and ATL. Action org.atl.dimensions.ENCODING additionally
checks exact encoded bits, radix transitions, sign, bounds, NaN/infinity and
both dimension overloads (111 total). These hidden methods are unavailable to
ordinary apps on our Android reference; their tests use AOSP encoding vectors,
not a claim of on-device parity. No hidden-API policy is modified.

Build Java/dex inside Alpine:

```sh
sh tests/dimensions/build.sh BUILD_DIR OUTPUT_DIR
```

On Mint, package AndroidManifest.xml and OUTPUT_DIR/classes.dex with SDK aapt
against android.jar, then sign with a test key. Install/start only this test
package on the emulator. The test writes files/results.json; require a fresh
artifact and the PASS marker. Log output alone is not a substitute for it.
