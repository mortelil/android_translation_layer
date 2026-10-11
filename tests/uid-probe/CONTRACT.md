# J-UID — Android logical UID semantics contract

Case APP-COMPAT / J. Finding: candidate `main` lacked
`Process.isApplicationUid(int)` (first YouTube-x86 Java failure
`NoSuchMethodError android.os.Process.isApplicationUid(I)Z`) and the related
predicates were constant stubs, while `Process.myUid()` returned the host uid.
Source contract: AOSP `android-17.0.0_r1` (commit `94b4c163`, provenance
`/home/morten/ATL/reference/aosp/`), `android/os/Process.java`,
`android/os/UserHandle.java`.

## Observation (frozen probe APK)
For every value in the frozen set
`{0, 1000, 1001, 2000, 9999, 10000, 19999, 20000, 90000, 98999, 99000, 99999, 100000, -1, 2147483647}`
the probe records the results of `Process.isApplicationUid(int)`,
`UserHandle.isApp(int)` and `UserHandle.isIsolated(int)`, plus the process
identity: `Process.myUid()`, `UserHandle.getUserId(myUid())`,
`UserHandle.getAppId(myUid())`, `Process.isApplicationUid(myUid())`,
`Process.isIsolated(myUid())` (and its public `isIsolatedUid`/`isIsolated()`
equivalents), `UserHandle.isApp(myUid())`, `UserHandle.isCore(myUid())`, and the
public constants `Process.FIRST_APPLICATION_UID` / `LAST_APPLICATION_UID`.
Emitted as one sentinel-wrapped JSON object (`JUID_JSON_BEGIN` / `JUID_JSON_END`,
completion marker `JUID_DONE`) and persisted to `files/results.json`.

The same frozen APK runs on the Android emulator and on ATL. Missing features are
emitted as errors or as a documented unreachable surface (null), never a fake zero.

## Reachability strategy (hidden / greylisted APIs)
The probe compiles against the public `android-37.0` SDK stubs
(`targetSdkVersion=24`, `minSdkVersion=24`).

* Public SDK methods — `Process.isApplicationUid(int)`, `Process.isIsolatedUid(int)`,
  `Process.isIsolated()`, `Process.myUid()`, `Process.FIRST_APPLICATION_UID` /
  `LAST_APPLICATION_UID` — are called **directly** (recorded as `direct`).
* Hidden surfaces — `UserHandle.isApp/isIsolated/isCore/getAppId/getUserId`,
  `Process.isIsolated(int)`, the isolated-UID constants, `PER_USER_RANGE` — are
  reached by **reflection** (recorded as `reflection`). `targetSdk 24` keeps
  `@UnsupportedAppUsage(maxTargetSdk=…)` members accessible to this app.
* A surface that genuinely cannot be reached is recorded as
  `"unreachable: <exception class: message>"` and its value as JSON `null`.

Observed environment consequence (documented, not fabricated):
`Process.FIRST_APP_ZYGOTE_ISOLATED_UID` / `LAST_APP_ZYGOTE_ISOLATED_UID` are
`@TestApi` only (not `@UnsupportedAppUsage`), so on the user-build emulator the
reflective field read is unresolved → recorded as `null`; ATL exposes 90000/98999.
The comparator treats such one-sided null constants as an incomparable surface,
never as a mismatch.

## Comparator rules (`verify_uid_semantics.py`, fail-closed)
* Both documents must be non-empty, sentinel-wrapped (or `--allow-bare`), a single
  JSON object with task id `J-UID`, distinct non-empty `run_id` per side,
  `thread=="main"`, `status=="OK"`, empty `exceptions`, the frozen `valueSet` in
  order, and a present `apk.sha256` **equal on both sides** (same frozen APK).
* `predicates`: exactly one row per frozen uid, in order, no duplicates, three
  real booleans each; candidate and reference compared **exactly** row by row;
  each side additionally checked against the AOSP-derived expected matrix (so two
  identically wrong sides cannot pass: `SPEC_MISMATCH`).
* `identity`: the raw `myUid` is **not** compared across sides (ATL deliberately
  reports a logical application UID, Android a real sandbox UID). Instead each
  side must satisfy the structural rule
  `myUid>0 && isApplicationUid(myUid) && isApp(myUid)==isApplicationUid(myUid) &&
   !isIsolated*(myUid) && getUserId(myUid)==0 &&
   getAppId(myUid)==myUid%100000 && 10000<=getAppId(myUid)<=19999 &&
   FIRST_APPLICATION_UID==10000 && LAST_APPLICATION_UID==19999`
  and the **candidate** must additionally report the pinned ATL values
  `myUid==10000`, `appId==10000`, `userId==0`.
* Exit codes: `0` accept, `1` reject (`REJECT <CODE> <reason>`), `2` usage/internal.

## Commands
* Build (container): `sh build.sh <outdir>` → unsigned aligned APK (sign on host).
* Self-test: `bash run_uid_selftests.sh` (fixtures + comparator; 27/27).
* Compare: `python3 verify_uid_semantics.py <android.json> <atl.json> [--allow-bare]`.

## Frozen APK
`uid-probe.apk` sha256
`b4bf9ab33cea2a3a3f2373112570ac7a67cd9ad39b79e166d7909344287a54c5`
(`org.atl.tests.uid/.ProbeActivity`, targetSdk 24, debuggable, V3-signed).
