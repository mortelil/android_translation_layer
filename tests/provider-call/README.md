# Cross-package provider calls

Build and run inside `atl_dev`, using the same mobile runtime environment as
`scripts/mobile/run.sh`:

```
sh tests/provider-call/run.sh /absolute/private/output-directory
```

The owner APK is installed into the test directory. The caller APK launches
without loading the owner's classes. The test asserts separate pids, owner
context, transport-derived calling pid/package, lookup through ActivityManager,
semicolon authority aliases, shared provider state across clients, and Bundle
arguments/results (including nested Intent data and an empty Parcelable array).
It also tests non-exported and permission-protected rejection, unknown authority,
exception propagation, null arguments, typed query results, oversized-query
refusal, use after release, repeated release, process death and reacquisition.
The runner requires both exit 0 and the final marker; it verifies the installed
owner APK bytes. Do not count an early provider-host READY line as success.

Production scope: `ContentProviderClient.call` and bounded query snapshots.
Query supports null, integer, float, string and blob cells. It closes the remote
cursor after materializing it. Limits are 4096 rows, 128 columns and 64000 bytes of
serialized result; larger values fail explicitly rather than being truncated.
This does not implement remote live cursors, observers, file access, mutations,
URI grants, signature permissions or stable-provider client process killing.
Stable and unstable acquisition currently use the same failure-returning client.

Providers run under their manifest process name in their own APK/runtime. Hosts
are shared within one requesting ATL process, with one lease per client and
shutdown on last successful client release. A failed/timed-out acquisition
keeps its host available for late publication/retry; this is an in-process
cache without an idle eviction policy. In-flight acquisitions hold leases too. They are not shared with independently launched ATL
windows or the service controller. Thus this is not Android-wide process
coalescing. All APKs still share the existing synthetic Android UID and Linux
user: this adds no OS sandbox. The provider bridge authenticates pid/package on
its private spawned channel and conservatively refuses cross-package providers
with read/write permissions. Provider-specific checks remain active.

References: AOSP frameworks/base android-17.0.0_r1,
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, ContentProvider.Transport.call/query,
ContentProviderClient, Intent parcel contract. Wire format and host integration
are original ATL code built on the existing ATLBinderConnection/ChildHost
transport; their original attribution remains in those files.

## Publication matrix

`sh tests/provider-call/run-all.sh OUTPUT` runs 11 isolated rows: legacy RPC,
initOrder/disabled providers, false onCreate result, constructor/attach/onCreate
exceptions, reentrant ContentResolver, retry after failure, concurrent acquire,
publication timeout with late success, and interrupted waiter. Set ATL_BUILD_DIR,
ATL_PREFIX and ATL_CORE_JAR to the candidate runtime and run under a display
(e.g. dbus-run-session + xvfb-run). No user profile or external account is used.

The legacy row also rejects oversized Unicode payloads and column names using
actual Parcel bytes, checks recovery after failed cursor serialization and a
Bundle writer that throws after adding a Binder, and tests ContentResolver.call
URI/authority delegation and reference release. Main-thread initialization uses
attempt tickets so old queued tasks/waiters cannot take over a retry.

OMP sources: 733af017, 149b0ed6, a74d31ab, ae36e981, and the ContentResolver-only
portion of 9203f3a2 (WebViewDatabase excluded). Service cleanup in ae36e981 was
already superseded by main #80 and is not reapplied. Local integration fixes
cover bounded serialized replies, per-attempt waiting, startup order, local
self-lookup, and waiting outside the global host map lock.
