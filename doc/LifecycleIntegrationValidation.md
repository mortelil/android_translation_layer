# Lifecycle foundation integration, 2026-10-15

The foundation integrates OMP candidate `47410ea5` with main `2d9a0294`.
OMP's original checkout is preserved. Integration commits retain the history and
AOSP/reuse attribution; this record supplements, rather than silently rewriting,
the historical evidence under tests/activity/evidence.

## Repairs found by independent validation

- The original transport failed its own immediate-drop churn test. A separate
  ACQUIRE frame could arrive after the caller had already dropped the proxy.
- The strong proxy cache had no production release owner. Transfer credits and
  weak-reference cleanup now implement normal app/Parcel lifetime. See
  [BinderReferenceLifetime.md](BinderReferenceLifetime.md).
- Monotonic handles were incorrectly restricted to 4096 lifetime allocations;
  the limit now applies to live objects. Deferred release ACKs now wait for pins.
- S12/S13c now kill real child processes through a probe-only transaction, testing
  crash caps and restart ordering on the actual process host.
- The remote placement verifier requires a genuine service identity transaction,
  not absence of local callbacks, and handles that transaction's barrier.
- Activating hierarchy-state dispatch exposed AntennaPod crashes in AndroidX
  Toolbar and RecyclerView. Empty superclass state, Parcelable creators and
  self-only save/restore now work. See
  [ViewStateCompatibility.md](ViewStateCompatibility.md).

## Measured coverage

Tests ran inside Alpine Toolbox on x86_64 using a separate `build-lifecycle`,
private data/cache directories and synthetic data. No Signal launch or private
account data was used. The selected ART/Bionic runtime is the foundation's locked
runtime; the dependency-lock revision updates are merge commits with identical
source trees to the previous pins.

| Test | Result and boundary |
|---|---|
| Transport | Full battery passes, including 4224 normal-API transfers, GC cleanup, retained/Parcel-owned proxies, publication ordering, acknowledged in-flight release, concurrency, nested calls, one-way ordering, FD transfer, invalid frames and death/load handling. The final ACK test waits for the owner to actually record a deferred ACK, not an arbitrary delay. |
| Services | Full matrix: 21 claimed rows pass, 5 real-host scenarios not run. Real-child S09/S10/S11c/S12/S13c/S15 pass; S09x broken-link refusal passes. The real-host arm is repeated after the view/Parcel repair. |
| Process host | P01–P10 pass: startup deadlines, peer identity, split refusal, provider/Application boot order, shared process lifetime and cleanup. |
| Activity | 191 path + 9 focus unit assertions pass. Live probe: 41 checks at each targetSdk 27/28/35 pass, repeated after the view repair. The standalone dalvikvm unit launcher has an existing shutdown abort after completion markers; this is not claimed as clean VM termination. |
| Android comparison | 16 local-service scenarios / 408 normalized events match the existing Android reference. SVC-014 now contains an extra identity pump step and is validated independently, not claimed as unchanged parity. |
| Verifier controls | 13 stored identity negative/positive controls pass; additional real-shape and missing/extra/misplaced-barrier checks pass. |
| Broad API smoke | Passes, including the new empty/wrapped/nested state, class-loader, text CREATOR, save flags and self-only/child traversal checks. |
| LocalSend | Renders Receive and responds to switching Send/Settings in a fresh profile. No file-transfer claim. |
| AntennaPod | Home to Subscriptions works after the hierarchy repair. Search still crashes at SearchFragment.setupToolbar with a null SearchView. Same failure reproduced on freshly built main 2d9a0294 using the same APK/runtime. No playback/search success claim. |

The full process/service matrices preceded the final view-only repair. The
final build was rechecked with the broad API suite, live activity probes,
transport battery and the real-host service arm. There is no claim that every
historical OMP test was repeated on the final tree.

## Reproduction and evidence

Local evidence root: `/home/morten/src/lifecycle-binder-20261015/`.
`release-source.txt` and `release-artifacts.sha256` identify the production source
and binaries. `env.sh` in the earlier `lifecycle-validation-20261015` directory
records the isolated runtime paths. These paths describe this run, not required
installation locations.

Reusable in-repo entry points (inside Alpine with ATL_PREFIX, ATL_CORE_JAR and
ATL_BUILD_DIR set for your build):

- `tests/transport/build.sh BUILD APK_DIR`, sign the APK, then
  `tests/transport/run.sh BUILD SIGNED_APK NEW_OUTPUT_DIR`.
- `tests/service/run.sh BUILD SIGNED_APK OUTPUT_DIR` for the full matrix;
  `SVC_GATE_LAUNCHES=realhost` selects the actual-child arm.
- `tests/process/run-matrix.sh SIGNED_APK OUTPUT_DIR P01 P02 P03 P04 P05 P06 P07 P08 P09 P10`.
- `tests/activity/run-unit.sh` and `tests/activity/probe/build.sh TARGET_SDK APK_DIR`.
- `tests/mobile-smoke/build.sh BUILD APK_DIR` and `tests/mobile-smoke/run.sh BUILD APK OUTPUT_DIR`
  under a private display/D-Bus session.
- `tests/local-service-probe/run_unsupported_remote_selftests.sh` and
  `python3 tests/local-service-probe/test_remote_barriers.py`.

## Remaining scope

No fresh Nura/ARM validation. Five service scenarios (S11a/b/d, S13a/b) still
have fake-backend coverage without their real-host fault arm. Cross-connection
Binder forwarding, cross-package permission enforcement and session restoration
after coordinator death remain excluded. GC cleanup is eventual. TextView state
is text-only, not full styled-text/selection/IME state. This is a bounded lifecycle
foundation, not full Android service or application compatibility.
