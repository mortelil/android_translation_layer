# BaseBundle double-value contracts

Double accessors belong to BaseBundle, not only Bundle. OsmAnd quick search
invokes BaseBundle.getDouble(key, default) and previously failed method lookup.
The test calls through a BaseBundle reference for both Bundle and
PersistableBundle: absent/null/wrong type, replacement/removal, null keys, NaN,
negative zero. It also verifies Bundle parcel round-trip of two coordinates.
Require BUNDLE_VALUES PASS checks=20 and a fresh results.json.

Reference: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/os/BaseBundle.java. The existing AOSP-derived methods are
moved out of Bundle rather than duplicated. Apache-2.0 header retained.
The OsmAnd OMP lane 578ff85 also identified this missing API. This patch does
not claim PersistableBundle parcel/XML persistence or add byte-array APIs to
BaseBundle (byte arrays belong to Bundle).

Build Java/dex inside Alpine:

```sh
sh tests/bundle-values/build.sh BUILD_DIR OUTPUT_DIR
```

On Mint, package AndroidManifest.xml and OUTPUT_DIR/classes.dex with SDK aapt
against android.jar, then sign with a test key. Install/start only this test
package on the emulator. The test writes files/results.json; require a fresh
artifact and the PASS marker. Log output alone is not a substitute for it.
