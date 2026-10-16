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
cursor after materializing it. Limits are 4096 rows, 128 columns and 64 KiB of
serialized result; larger values fail explicitly rather than being truncated.
This does not implement remote live cursors, observers, file access, mutations,
URI grants, signature permissions or stable-provider client process killing.
Stable and unstable acquisition currently use the same failure-returning client.

Providers run under their manifest process name in their own APK/runtime. Hosts
are shared within one requesting ATL process, with one lease per client and
shutdown on last release. They are not shared with independently launched ATL
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
