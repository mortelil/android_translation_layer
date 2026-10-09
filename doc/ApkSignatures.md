# Optional verified APK signing metadata

ATL's original certificate collector handles JAR/v1 signatures. APKs signed only
with v2/v3 need a verifier for those schemes before their certificates can be
reported to an application. `tools/apk-verifier/VerifyApk.java` delegates this to
AOSP's `com.android.tools.build:apksig:8.6.1` under OpenJDK, outside ART.
It does not trust certificates merely extracted from an APK signing block.

Inside the Alpine development container:

```sh
ATL_PREFIX=/path/to/runtime sh scripts/mobile/build-apk-verifier.sh
export ATL_APK_VERIFIER=/path/to/runtime/libexec/atl-apk-verifier/run
sh scripts/mobile/run.sh /path/to/app.apk
```

The helper requires Alpine OpenJDK 8 and curl. It is optional and is not enabled
by ordinary build/launch scripts. `ATL_APKSIG_JAR` can point to a previously
fetched jar; its SHA-256 must match the pinned dependency:

```
c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89
```

Dependency URL: https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.6.1/apksig-8.6.1.jar

**Credit and license:** apksig is developed by the Android Open Source Project,
licensed under Apache-2.0. The downloaded jar retains its LICENSE. No apksig
source or binary is copied into this repository. The helper and bridge were
written for this fork with AI assistance. See [AOSP apksig](https://android.googlesource.com/platform/tools/apksig/)
and [APK signature verification](https://source.android.com/docs/security/features/apksigning/v2).

The helper verifies against ATL's declared Android API level (1–35; newer levels
fail explicitly). Only after successful verification does it output current
signers and any verified rotation lineage. The ART bridge limits runtime to
60 seconds and output to 256 KiB, validates X.509 certificate records, and rejects
failed/malformed output. A configured verifier failure does not fall back to
unverified certificate extraction. `GET_SIGNING_CERTIFICATES` exposes current
signers/history; legacy `GET_SIGNATURES` exposes the oldest certificate after a
verified single-signer rotation. Independent multiple signers have no rotation
history. Returned arrays are defensive copies.

This is **package metadata support, not an installation security boundary**.
Existing ATL loaders can continue launching unsigned packages after collection
fails, and the older GMS compatibility signature overrides remain outside this
feature. There is no Android per-app sandbox, installer trust store, rollback
protection, or cross-process Binder authentication. Verification is against the
APK path; concurrent replacement of that path is not prevented. These limits
must be addressed before advertising a secure installation policy.

Tests (after building the helper and smoke APK):

```sh
python3 tests/apk-verifier/run.py /path/to/runtime/libexec/atl-apk-verifier/run \
  build/mobile-tests/smoke/mobile-smoke.apk
xvfb-run -a sh scripts/mobile/test.sh
```

The first command generates disposable keys and signs synthetic smoke APKs with
v2/v3. It checks exact signer certificates, multiple independent signers, API
bounds, and rejection of modified contents, truncated/unsigned/missing APKs.
The smoke tests cover signature-query flags, defensive copies, rotation metadata,
and malformed/nonzero/oversized helper output under ART. A real rotated APK
fixture and forced timeout/interruption tests remain to be added; rotation
metadata tests alone are not end-to-end proof of the rotation protocol.
