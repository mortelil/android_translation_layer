# APK/split identity and container regression tests

The resolver selects the base by its manifest's absent/empty `split` attribute,
not filename or dex presence. It checks package, full versionCode (including zero
and versionCodeMajor), unique split names, dependencies and the current signer set
of **every** member, even an ABI split excluded from loading. Only native-only,
non-feature splits of another ABI may be omitted. Code-bearing feature splits
are included in the dex path; resource/native-only APKs are not fed to ART.
The early native launcher resolves before loading app code/libraries; Build.VERSION
sees the resolved base manifest and ApplicationInfo contains split source paths.

APKM members are copied into a fresh mode-0700 directory under appdata using
CREATE_NEW, each in a separate generated directory. No reused metadata cache,
member-controlled directories, path flattening or recursive deletion. Rejects
traversal, absolute/backslash/colon member names, duplicate ZIP entries, over
256 APKs, over 100,000 ZIP entries, over 1 GiB per APK or 2 GiB total (streamed).
Manifest reads are bounded at 8 MiB. Normal shutdown removes only recorded paths;
forced termination can leave a private `atl-splits-*` directory behind.

## Reproduction

`build.sh OUTPUT` builds the original signed base/native/language/density family
using a JDK, Android SDK build-tools, clang cross-target support, framework-res.apk
and a debug keystore. All paths are overridable. SDK binaries run on Mint;
Alpine binaries must run inside Toolbox. On this machine Java was compiled/dexed
inside Alpine and SDK signing ran on Mint; do not execute Alpine binaries on Mint.

`compare.py --help` compares collected JSON with the frozen Android reference;
`self-test.py` exercises the comparator. The original comparator deliberately
expects its fixed filenames and a base with dex; do not loosen it for other cases.

`archive-cases.py SIGNED_FAMILY NEW_OUTPUT` generates traversal, colon-name,
duplicate entry/base/split, missing base, corrupt manifest/signature, excessive
member count and a *positive* flattened-name collision case. Launch each in a
separate private appdata/cache directory. Invalid containers must fail before app
code. Collision success is asserted by both assets and the native code actually
loading from distinct members, not by the fixed-family comparator's filename test.

`extended/build.py SDK_ROOT BUILD_TOOLS_VERSION DEBUG_KEYSTORE KEY_ALIAS NEW_OUTPUT`
builds independent fixtures (test-key password `android`). It needs a host JDK;
`DEX_FILE` can instead point to FeatureActivity dex compiled inside Alpine, and
`KEYTOOL` selects the host keytool. It generates:

- `dexless-base.apkm`: feature first in ZIP, dexless base, code/assets in feature.
  Expect `FEATURE_CONTRACT_PASS`, results.json `status: OK`, SDK >= 24 and correct
  ApplicationInfo source/split paths. No APK filename heuristic can pass this.
- version zero vs 1, versionCodeMajor mismatch, package mismatch, missing feature
  dependency, and a correctly signed split from another signer: all exit 14 with
  SPLIT_MISMATCH. The different-signer test is distinct from signature corruption.

## Limits

This is launch-time selection/validation, not a package installer or dynamic-feature
manager. Split manifest components are not merged into PackageParser; the feature
code test declares its Activity in the base. Full Android configuration/resource
selection and isolated feature classloaders are not claimed. Signer rotation is
conservative: current signer sets must match. Multi-member sets require
ATL_APK_VERIFIER; ordinary single-APK signing behavior remains unchanged.

Tests are x86_64. A real app reaching a later unsupported API is evidence for the
loader only, not proof that the app works. Keep malformed/ABI rejection distinct
from graphics, Java API and app-runtime failures.
