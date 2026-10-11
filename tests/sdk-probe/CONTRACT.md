# D-SDK — SDK/API profile, resource-version consistency, Build.getSerial

Frozen probe APK family + fail-closed comparator for the D-SDK contract
(`agent-work/APP-COMPAT/D-SDK/spec.md`). One code base, two APKs that pin BOTH
branches of the corrected profile `default = max(apk minSdk, 21)` AND both sides
of the getSerial Q boundary.

## APKs (built under `app-compat/tests/sdk-probe/build.sh`; signed on the host)

| file | minSdk | targetSdk | default expected | sha256 |
|---|---|---|---|---|
| `sdk-dprobe-m21-t28.apk` | 21 | 28 | 21 (RELEASE "5.0") | see `D-SDK/probe-attempt-2/artifact-sha256.txt` |
| `sdk-dprobe-m28-t30.apk` | 28 | 30 | 28 (RELEASE "9") | see `D-SDK/probe-attempt-2/artifact-sha256.txt` |

(Superseded: `probe-attempt-1/` minSdk-24 family with a fixed `expect_default_sdk: 21`.)

Package `org.atl.tests.sdk`, activity `org.atl.tests.sdk.ProbeActivity`.
Declares `android.permission.READ_PHONE_STATE` (dangerous) so the grant/deny
cases are real on Android.

## Observations (single sentinel-wrapped JSON)

`SDKPROBE_JSON_BEGIN` … `SDKPROBE_JSON_END`, the same object in
`files/results.json`, then the completion marker `SDKPROBE_DONE`. Completion is
the marker / results file — never process exit (runbook §8).

Fields: `task_id`, `run_kind`, `scenario`, `run_id`, `status`, `exceptions`,
`apk{min_sdk,target_sdk}`, `build_version{SDK_INT,SDK,RELEASE,SDK_CODENAME,
RESOURCES_SDK_INT,PREVIEW_SDK_INT}`, `system_properties{ro.build.version.sdk,
.codename,.release,read_errors}`, `resource_selection{selected,value,candidates,
expected_for_level,agrees}`, `configuration{sdkVersion}`, `permissions{available,
READ_PHONE_STATE,READ_PRIVILEGED_PHONE_STATE}`, `get_serial{attempted,value,
exception,message,classification}`.

- Version fields are read **reflectively** so a runtime that does not implement
  one (e.g. Android hidden-API block of `SDK_CODENAME` / `RESOURCES_SDK_INT`)
  is an explicit `read_errors` entry, not a crash.
- Strings are bundled in `values/` (`sentinel-base`), `values-v21/`
  (`sentinel-v21`) and `values-v28/` (`sentinel-v28`); `resource_selection.selected`
  says which qualifier the framework chose. `expected_for_level(level)` =
  `>=28 -> v28`, `>=21 -> v21`, else `base`.
- `run_kind` / `scenario` come from the Android intent extras
  (`--es run_kind ... --es scenario ...`) or, on ATL, the `SDKPROBE_RUN_KIND` /
  `SDKPROBE_SCENARIO` environment variables the helper injects.

## getSerial semantics encoded by the comparator (AOSP DeviceIdentifiersPolicy)

ATL uses the Android Q-and-later identifier access policy, independent of its
reported compatibility API level. It does not emulate historic platform grants.
The observed permission state of the process and targetSdk determine access:

- `READ_PRIVILEGED_PHONE_STATE` granted → UNKNOWN or OTHER (ro.serialno);
- else targetSdk < 29 **and** `READ_PHONE_STATE` granted → `UNKNOWN`;
- else → `java.lang.SecurityException` (classification `SECURITY_EXCEPTION`).

A normal app can never hold the privileged permission, so a fabricated value in
that branch is rejected as `SERIAL_SEMANTICS`. Cross-run rule: the ATL default
and `--sdk-int 28` override classifications must be identical (`SERIAL_DIVERGENCE`).

## Comparator

```
python3 verify_sdk_profile.py <family.json> [--allow-bare]
bash run_sdk_selftests.sh          # 23/23 expected
```

`family.json` names, per run (`name`, `min_sdk`, `target_sdk`, optional
`override_sdk`, `scenarios`) and scenario, the three documents `android` /
`atl_default` / `atl_override` (relative paths). The default expectation is
DERIVED per run as `max(<doc.apk.min_sdk>, PLATFORM_SDK_FLOOR=21)` - never a fixed
constant - so a below-floor OR above-floor (inflated) default rejects. Fail-closed codes:
EMPTY/MISSING/UNREADABLE_EVIDENCE, JSON_PARSE, SENTINEL_INVALID/MISSING_SENTINEL,
NOT_AN_OBJECT, MISSING_FIELD, WRONG_TASK, STATUS_NOT_OK, EXCEPTIONS_PRESENT,
WRONG_RUN_KIND, WRONG_SCENARIO, DUPLICATE_SCENARIO, CONFIG_MISMATCH,
INVALID_NUMBER, INCONSISTENT_SDK, PROP_MISMATCH, RESOURCE_MISMATCH,
SERIAL_SEMANTICS, DEFAULT_PROFILE_WRONG, OVERRIDE_NOT_APPLIED, RELEASE_MISMATCH,
SERIAL_DIVERGENCE. Exit 0 `OK …`, 1 `REJECT <CODE>`, 2 usage/internal.

## Build / sign / run

```
# build (container)
toolbox run --container atl_dev sh -lc \
  '/home/morten/ATL/app-compat/tests/sdk-probe/build.sh \
     /home/morten/ATL/app-compat/tests/sdk-probe/build'
# sign (host) — see D-SDK/probe-report.txt for the exact apksigner line
```

Android reference (host, `emulator-5554`): install, `pm revoke`/`pm grant`
`READ_PHONE_STATE`, `am force-stop`, `am start ... --es run_kind android --es
scenario <scen>`, then `run-as org.atl.tests.sdk cat files/results.json`.

ATL (host, exercises the candidate): `run-sdk-atl.sh <apk> <out> <run_kind>
<scenario> <timeout> [sdk_int]` — default omits `sdk_int`, override passes
`28`; it forwards `--sdk-int` to `scripts/mobile/run.sh` via the container
command.