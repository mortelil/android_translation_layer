# APP-COMPAT integration review

The stopped OMP work was reviewed against main `4e4ea5c41842306d700218ce3a50a73e606c26b7`.
The original checkouts, dirty generated files and untracked SurfaceControl code
were preserved. This integration intentionally excludes F, G and K.

## Included scope

- **A / JNI:** uncaught errors at native-owned Application/startup boundaries
  retain the original Throwable instead of proceeding to an invalid JNI call.
  Label lookup/conversion now checks pending exceptions between operations.
  This is not permission to terminate exceptions that Java can still catch.
- **J / identity:** logical Android application UID 10000 and Android UserHandle
  predicates/ranges; no claim of Linux UID changes or per-app sandboxing.
- **B / callbacks:** ComponentCallbacks2 type/constants, snapshot registration
  and direct dispatch through Application/Context. List modifications during
  dispatch affect later dispatches. Linux-triggered configuration/trim dispatch
  is **not verified or supplied** by this patch.
- **C / WebSettings, partial:** a stable settings object bound to the actual
  WebKit settings; user-agent and supported boolean settings. JavaScript and DOM
  storage start disabled. The speculative JavaScript-based automatic focusing
  was removed; `setNeedInitialFocus` explicitly warns NOT_SUPPORTED. WebView
  remains gated by `ATL_UGLY_ENABLE_WEBVIEW=1`. Unsupported controls are reported,
  not enforced; do not assume Android WebView security or compatibility parity.
  Database flag round-trip does not establish working WebSQL.
- **D / profile:** default compatibility API `max(minSdk, 21)`, explicit override
  retained. Restores main's early immutable initialization instead of mutating
  the API after Application construction. Java and native API are synchronized
  before application construction. getSerial uses the Q-and-later identifier
  policy regardless of compatibility API, exposes no Linux hardware serial,
  and does not implement carrier/device-owner backends or historical grants.
- **E / diagnostics:** unsupported native ABI gets a clear primary-launch
  diagnostic and exit 12. Secondary loads retain ordinary loader errors; an
  embedded APK asset in a real APK is not treated as an installed split. A host
  ABI directory does not prove every required library is present: actual linker
  errors remain authoritative. APKM diagnostics do not imply APKM installation.
- **H / dependency:** scheduler wrappers and ABI/error tests delivered separately
  in mortelil/bionic_translation PR #1. ATL pins the remotely available
  `b7b006bfb3fb70dae298925b571c52faadba75d2` (ancestor of its merged main).

## Held work

- **F / APKM:** base identification relies on dex rather than manifest identity;
  unreadable identities can be skipped; extraction flattens member names and can
  collide; cache cleanup follows directories without sufficient symlink
  protection. Identity/signing and bounded extraction need a focused redesign.
- **G / Path.op:** independent counterexample: union of `(0,0,1,1)` with
  `(10000000,0,100,100)` returns true but loses the small rectangle. Global
  extent-based snapping is not a correct general boolean operation. No claim
  that aliasing/fill-rule tests compensate for this failure.
- **K / SurfaceControl:** the uncommitted implementation reports completion
  without presentation and reports a previous-buffer release fence of -1
  without implementing corresponding buffer release. Refcount, queued cycle
  validation, callback deletion and transaction reuse also need work. These
  13 symbols are excluded; the old linker failure is preferable to false
  presentation success. No compositor is implemented and no K probe is counted
  as accepted.

## Candidate validation

Built together in Alpine Toolbox `atl_dev`, x86_64, with a private Bionic prefix
and the existing pinned ART runtime. Dependency checker passed without bypass.
The Bionic revision was fetched directly from GitHub into an empty Git repository.
Loaded process maps identify candidate libc_bio, libtranslation_layer_main and
candidate api-impl dex; private caches and appdata avoid shared-runtime evidence.

- Bionic: 18/18 including errno, queries and a four-byte sched_param canary;
  the host-musl negative control rejects all 18.
- JNI startup H/I: original exceptions preserved, CheckJNI enabled, no secondary
  pending-exception abort. JNI analyzer controls: 26/26 plus startup 21/21.
- UID frozen Android/ATL probe: 15 inputs x 3 predicates and identity structure;
  analyzer controls 27/27.
- Callbacks frozen probe: 7 constants, registration and exact 23-event direct
  dispatch match Android. System-triggered coverage is NOT_TRIGGERED on ATL;
  analyzer controls 33/33.
- Web probe with actual WebKit enabled: UA and supported setting round-trips
  match. **Full comparator REJECT SCENARIO_MISMATCH**: evaluateJavascript is
  still a stub, so DOM/focus observations time out. This is the existing gap,
  not a full PASS. Analyzer controls 24/24 remain strict.
- SDK: eight fresh runs across minSdk 21/28, targetSdk 28/30, default/override,
  and two permission scenarios; comparator accepts with documented gaps:
  hidden Android resource field unreadable; permission-granted comparisons
  unavailable where Android and ATL grants differ. Analyzer controls 23/23.
- ABI scanner: six focused archive tests, including embedded ARM asset and
  split-set cases; actual ARM-only Morphe produces ABI_BLOCKED and exits 12.
- mobile-smoke: passed, including bitmap, fonts/layout, input, networking,
  cleartext policy and existing media/provider contracts.

This is a desktop compatibility integration, not a new Nura verification or
successful Google sign-in, messaging, upload, video playback or file transfer.
App observations and local reproduction/evidence paths are recorded in the
local delivery report rather than committing runtime logs or APKs.

## Repeating the checks

Use `scripts/mobile/check-dependencies.py` with checkouts matching the lock,
then build via Meson inside Alpine. Run `tests/mobile-smoke/build.sh` and
`run.sh` with that build. Existing `tests/{jni-exceptions,uid-probe,bcc-probe,
web-probe,sdk-probe}` contain probe sources, builders and analyzers. Run their
`run_*selftests.sh` scripts with **bash**, not sh. `tests/native-abi/test_diagnostics.py`
requires a JDK; it compiles the real scanner with only Build/Slog identity shims,
not an Android package loader. The Bionic PR contains its scheduler test command.

Keep probe APKs and Android references identical between comparisons; record
actual library/JAR origin and interpret explicit GAP/NOT_TRIGGERED/REJECT states.
Do not substitute helper exit 0 or a live process for functionality.

### Desktop app spot checks

- LocalSend: Receive → Send → Settings → Receive, visible content changes;
  window close exits 0. No transfer attempted.
- Messenger: login renders, dummy password text appears masked; close exits 0.
  No authentication attempted.
- Immich: server screen renders and accepts `http://example.invalid` without
  submission; close exits 0. Settings click did not establish navigation, so
  settings navigation is not counted as passed. No server or photo access.
- ReVanced Manager: welcome/permission page visible; not installation tested.
- microG: settings page visible; Google login not tested in this pass.
- ARM-only Morphe: correctly blocked with exit 12.
- x86 YouTube (explicit base/split paths): not ABI-blocked; fails on missing
  `StrictMode.ThreadPolicy.Builder.penaltyListener(Executor, OnThreadViolationListener)`.
  This method is unchanged by this integration. No working YouTube claim.

The final rebuild after comment/documentation cleanup is additionally checked
with mobile-smoke; no implementation changes occurred after the combined probes.
