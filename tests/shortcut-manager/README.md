# ShortcutManager / ShortcutInfo probe

Single-APK probe for the ATL `android.content.pm.ShortcutManager` /
`ShortcutInfo` milestone (dynamic shortcut publishing and its ATL-only durable
store). `Probe.java` runs on ATL and an AOSP API-37 reference emulator. Store
files, the cross-process lock and crash/migration modes are ATL-only. ATL has no
system server, so publisher-side state lives in `android.content.pm.ShortcutManager`.

Reference consulted while writing the implementation and this probe: AOSP
`frameworks/base`, tag `android-17.0.0_r1`, commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`, `core/java/android/content/pm/ShortcutManager.java`,
`core/java/android/content/pm/ShortcutInfo.java`, and
`services/core/java/com/android/server/pm/ShortcutService.java` /
`ShortcutPackage.java` for per-activity quota and rank eviction.

## Build

Build ATL in the isolated `atl_dev` lane environment using the resource wrapper:

```sh
python3 /home/morten/.omp/profiles/atl-parallel-ab/agent/resources.py \
  --team osmand --kind build --timeout 3000 -- toolbox run --container atl_dev sh -lc \
  "ATL_WORKSPACE=/home/morten/ATL/parallel-apps-ab/osmand/env \
   ATL_PREFIX=/home/morten/ATL/parallel-apps-ab/osmand/prefix \
   ATL_BUILD_DIR=/home/morten/ATL/parallel-apps-ab/osmand/repo/build-mobile JOBS=2 \
   sh /home/morten/ATL/parallel-apps-ab/osmand/repo/scripts/mobile/build.sh"
```

Build the probe against that lane's `hax.jar` and `framework-res.apk`, then
sign the aligned output on the host with the lane's test key:

```sh
python3 /home/morten/.omp/profiles/atl-parallel-ab/agent/resources.py \
  --team osmand --kind build --timeout 600 -- toolbox run --container atl_dev sh -lc \
  "ATL_WORKSPACE=/home/morten/ATL/parallel-apps-ab/osmand/env \
   ATL_PREFIX=/home/morten/ATL/parallel-apps-ab/osmand/prefix \
   sh /home/morten/ATL/parallel-apps-ab/osmand/repo/tests/shortcut-manager/build.sh \
   /home/morten/ATL/parallel-apps-ab/osmand/repo/build-mobile \
   /home/morten/ATL/agent-work/PARALLEL-APPS-AB/osmand/probe/shortcut-manager"

/home/morten/Android/Sdk/build-tools/37.0.0/apksigner sign \
  --ks /home/morten/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  /home/morten/ATL/agent-work/PARALLEL-APPS-AB/osmand/probe/shortcut-manager/shortcut-manager.apk
```

`build.sh` runs `zipalign` before signing; do not re-align after signing. The
probe has no special permissions. Record the final signed APK hash with `sha256sum`.

## ATL scenario suite

Run the host-side driver under the lane resource wrapper; do not wrap the driver
again per child. It starts each probe through the shared runner while retaining
the same lane app-data directory, including the concurrent process scenarios.
The driver creates a fresh timestamp/PID subdirectory under the requested
output base so stale runner logs/results cannot be mistaken for new evidence.
Each finite scenario requires both `ATL-PROBE STATUS=OK` and a `PASS` result
JSON. Deliberately killed processes are the only expected runner exit-3 cases.

```sh
python3 /home/morten/.omp/profiles/atl-parallel-ab/agent/resources.py \
  --team osmand --kind atl --timeout 600 -- \
  sh /home/morten/ATL/parallel-apps-ab/osmand/repo/tests/shortcut-manager/run-atl-scenarios.sh \
  /home/morten/ATL/agent-work/PARALLEL-APPS-AB/osmand/probe/shortcut-manager/shortcut-manager.apk \
  /home/morten/ATL/agent-work/PARALLEL-APPS-AB/osmand/probe/scenarios
```

The suite covers:

* ordinary set/add/update/remove/remove-all/lookups, returned-object isolation,
  restart persistence and a positive package-isolation publish into the separate
  `android` context; API-specific package identity behavior is not forced on AOSP.
  The `checks` mode is driven twice in a row against the same app-data directory
  (no clearing): at the start of a run that finds an existing store, the ATL-only
  check decodes the durable file independently and requires the fresh manager's
  `getDynamicShortcuts()` to match it exactly, so the second launch proves the
  state the first launch committed is reloaded rather than silently reset;
* the ATL-only typed v3 schema, corrupt/truncated/unknown-version recovery,
  injected interrupted-write handling and the **same-length rename-failure**
  regression;
* two independent `peer` processes doing concurrent read/modify/write while raw
  reader threads validate stores, then `verify` requires all 24 updates;
* a lock owner held for 45 seconds plus two contenders. The contenders examine
  exclusion after 33 seconds, beyond the former 30-second stale-reclamation
  threshold, and then complete after release;
* a holder killed with `SIGKILL` while holding the stable lock, followed by an
  immediate successful commit that verifies the lock sentinel is intact;
* a `crashloop` process killed during large repeated commits, followed by a
  complete-store decode and immediate successful append; and
* an exact checked-in legacy v2 fixture migration to v3, followed by a fresh
  process comparing every encoded field after restart.

The shared `run-atl-probe-lane.sh` performs an initial one-second launcher
liveness check before marker polling. `Probe.java` remains alive for 1.5 seconds
after writing each finite mode's PASS/FAIL result, allowing the runner to collect
the marker and fresh JSON instead of reporting a false `INTERNAL_ERROR` for a
completed fast run. This is test-harness synchronization only; assertion and
result semantics are unchanged. `hold` and `crashloop` are killed externally by
the host driver while active; the driver waits two seconds after the ready marker
so the shared runner is already past its startup liveness check.

The app-data root is
`/home/morten/ATL/parallel-apps-ab/osmand/data/apps`; the store filename for the
probe is `files/shortcuts.bin.org.atl.shortcutmanager.bin`, with a stable sibling
`.lock`. The cross-process probes intentionally share that data path.

## API-37 Android reference

Run only the public `checks` mode on the real `emulator-5554` AOSP API-37
reference using `run-android-reference.sh` (not the ATL probe runner). The helper
installs the same signed APK, resets system shortcut throttling, starts the
activity, saves installation/start/logcat evidence and requires a public
`SHORTCUT_MANAGER PASS checks=N` marker. ATL-only store, lock, crash and
migration checks are skipped on Android.

```sh
python3 /home/morten/.omp/profiles/atl-parallel-ab/agent/resources.py \
  --team osmand --kind android --timeout 600 -- \
  sh /home/morten/ATL/parallel-apps-ab/osmand/repo/tests/shortcut-manager/run-android-reference.sh \
  /home/morten/ATL/agent-work/PARALLEL-APPS-AB/osmand/probe/shortcut-manager/shortcut-manager.apk \
  /home/morten/ATL/agent-work/PARALLEL-APPS-AB/osmand/probe/api37-checks
```

## Store-write regression details

`AtomicFile.finishWrite()` only logs a failed rename; `ShortcutManager.write`
therefore proves commit success by requiring both that `<store>.new` was consumed
and that the destination contains byte-for-byte the new data. A length-only
comparison would incorrectly accept an unchanged old file of equal length. The
probe creates two different equal-length store states, returns to the first,
pre-creates a writable temp file and makes the directory non-writable so the
temp write succeeds but `rename(2)` fails with `EACCES`. It then requires a
failed publish, unchanged visible/file state and a later successful write that
consumes the leftover temp file.

## Fixture provenance and limits

`fixtures/previous-v2-14.bin` (4204 bytes, SHA-256
`732f0488fd17744bf7a9a1082f03375457f6f0544fe9e7b5354878e73375f48f`, 14 entries)
is the byte-exact v2 ATL store: magic `0x41544C53` (`ATLS`), version `2`, entry
count, and `ShortcutInfo` Parcel-framed records in the frozen
`PARCEL_VERSION` 1 layout. The probe decodes the fixture independently of the
implementation and compares every encoded field before and after migration.

* `Icon` is not Parcelable in ATL, so icons are not persisted across restart.
* ATL keeps one file per package (not per activity); the stable lock is a sibling.
* A `crashloop` kill cannot guarantee every `SIGKILL` lands inside the
  `write()`/`rename()` window. The deterministic interrupted-write and
  same-length rename-failure regressions cover those failure cases
  unconditionally; the kill probe separately verifies store integrity after
  abrupt process death.
* `Process.killProcess` cannot provide the required hard-kill semantics from
  inside the probe, so the host driver kills the printed app PID.
