# Validation and remaining work

Publication preparation, 2026-10-08. The three source checkouts were built from
fresh local clones in an existing Alpine edge x86_64 Toolbox environment. The
build used the private overlay created by `scripts/mobile/build.sh`, including a
newly compiled libutils and WolfSSL provider. System libraries and the working
Immich installation were not replaced. The host is Linux Mint; all Alpine
executables were run in Alpine. This is not a clean-container installation test.

`scripts/mobile/test.sh` completed with status 0. The test APKs use dedicated
appdata and generated red PNG fixtures; no Immich APK, account or real media is
needed. The TLS test initializes a context without opening a socket. The network
callback test registers/unregisters locally; it does not contact a server.

Changed ATL source and Java test files were checked with the project's custom
clang-format 22.1.0-rc3. Shell syntax checks and git diff whitespace checks pass.
Existing license headers were checked against the upstream base. New additions
were screened for local paths, known private addresses, credential markers and
unexpected binary/media files. Only source, documentation and synthetic tests
are included in the new commits; raw debugging/session logs are kept private.

```
OK (6 tests)
PASS: RGBA pixel content, buffer positions, bounds, recycled bitmap and multicast lock
PASS: WolfSSL context initializes without client certificate; no socket connection made
PASS: Range boundaries, intersections, extensions, ExoPlayer interval; unavailable codec measurements
PASS: descendant invalidation and child stacking
PASS: synthetic media index, .nomedia, nullable GPS, read and thumbnail
PASS: DynamicLayout follows append and clear with real Pango line positions
PASS: InputConnection override, insertion, cursor, selection, composition and deletion
PASS: cleartext base policy, domain deny, subdomain inheritance and specific override
PASS: network request immutability, callback lifecycle and code cache
ATL TEST APK PASSED
PASS: file operations, bounds checks, atfork ordering and DSO cleanup
PASS: Dart process-scope calloc/free, ABI override priority, missing symbol failure
PASS: native fd readiness, callback removal, wakeup, delayed message deadline, idle state
```

The Matrix fix separately has a negative control on the recorded upstream base:
four of six tests fail without it; all six pass with only that fix. Its test
runner selects the build directory's libraries and checks JUnit completion,
because process exit status alone does not establish passing instrumentation.

The smoke tests cover representative behavior, not the entire Android contract.
The original Range test used lambdas; the published APK version uses anonymous
Runnable classes to work with the older dx toolchain. An explicit appdata mkdir
was also added to make a first run work. The full scripts were then rerun.

The original source underlying this fork was also tested on ARM64/Plasma Mobile:
Immich login/server photos, local-image viewing, a small backup, progressing video
frames, pause/back and timeline scrolling were observed. The publication cleanup
has not been redeployed or GUI-tested on the phone. No claim is made that every
APK/version/device works. TextureView/Places, advanced IME, media metadata,
network model, automatic scale/insets and video performance remain limited.

Next useful tests: a fresh container build, the published branch on ARM64,
repeated video open/close and resize, suspend/resume, complex text composition,
large synthetic media sets, and callback/queue/thread lifetime stress tests.
No independent human expert review or full Android CTS run is claimed.
