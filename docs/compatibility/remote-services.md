# Experimental service processes and Binder RPC

This is a first milestone, enabled only with `ATL_ENABLE_REMOTE_SERVICES=1`.
It is **not** an Android system service manager, security sandbox, or kernel Binder
implementation. The default launcher still rejects remote service starts/binds.

## Implemented scope

* A declared non-isolated service in a different `android:process` is hosted by a
  separately exec'd ATL/ART process, with its own Application and matching providers.
  No Activity is launched/presented there. GTK/display access is still required.
* One child is reused per package/process within one launcher session. Process name,
  PID, and the known parent/child process entries are exposed to the application.
* Start/stop and bind/unbind use the existing service registry in the child. Service
  lifecycle callbacks run on its main looper; client ServiceConnection callbacks run
  on the client main looper (or its existing ContextImpl executor adapter).
* Connection-scoped Binder handles preserve object identity when sent back to their
  owner. A remote object has no local interface. Synchronous calls, returned Binder
  objects, callback Binders, remote runtime exceptions, and per-object ordered
  one-way calls are transported. Nested synchronous callbacks reenter the waiting
  caller thread rather than deadlocking it.
* Caller PID is obtained from the process that ATL launched, not a message field.
  Calling identity can be cleared/restored. ATL still uses its existing **one logical
  application UID**, not separate Android UIDs or a permission boundary.
* writeFileDescriptor duplicates the source FD, so it may be closed before transact.
  File descriptors travel via SCM_RIGHTS. Received descriptors are CLOEXEC and owned
  by their Parcel; readFileDescriptor duplicates them for the recipient, allowing
  the Parcel to be recycled independently. A raw integer without matching descriptor
  metadata is rejected instead of exposing a process-local FD.
* EOF fails outstanding calls and notifies Binder death recipients and bound clients.
  This milestone does not restart dead services automatically: onBindingDied tells
  clients that they must unbind/rebind. Children exit when their parent connection
  disappears; a reaper waits for each owned child.

## Transport and limits

A private AF_UNIX SOCK_SEQPACKET socketpair is created before exec. Only the child
end is inherited, and it is then marked CLOEXEC. [GLib process spawning](https://docs.gtk.org/glib/func.spawn_async_with_pipes_and_fds.html)
closes unrelated inherited descriptors. There is no filesystem/public
socket, TCP listener, discoverable endpoint, or authentication credential to log.

The framing is ATL-specific; it transports ATL's current Parcel representation,
**not Android's binary Parcel layout**. Both processes must run the same build.
It is deliberately bounded: 128 KiB/message, 64 descriptors/message, 4096 exported
objects/connection, 16 dispatch workers, 256 outstanding synchronous calls, and 256 queued one-way calls/connection.
Calls time out after 30 seconds and disconnect the peer. A timeout does not prove
that the remote operation had no side effects, and calls are never retried silently.

## Explicitly outside this milestone

* Isolated process UIDs, Android SELinux/permissions and cross-user policy.
* Cross-launcher discovery and sharing a single service between independent app
  launches; forwarding Binder objects through a third connection; services in
  split APK packages; service-to-another-process bindings from a child.
* Binder distributed reference counting: handles and cached service processes are
  retained for the connection/session lifetime. Repeated long-running use can hit
  the explicit object limit. This must be addressed before calling it general support.
* Automatic process restart, sticky/redelivered starts, foreground service policy,
  full ActivityManager/process priority and memory-pressure management.
* Non-auto-create binding before a remote process exists (rather than starting
  an Application as a hidden side effect); remote service intents with ClipData/selectors, and using one ServiceConnection for different
  remote components. These fail explicitly. Intent's existing Parcelable stub is
  not used: supported intent fields and extras are encoded by the control protocol.
* Parcel.appendFrom with Binder/descriptor objects currently fails explicitly rather
  than silently dropping their metadata. Arbitrary Parcelable stubs elsewhere in
  ATL can still prevent particular AIDL protocols from working.
* Arbitrary command-line ART flags are not copied to a child; the selected SDK and
  inherited runtime environment are preserved. Custom launcher modes need further tests.

This is a development feature, not yet a claim that microG account registration or
DroidGuard validation succeeds. No challenge response is fabricated and no caller
identity/signature check is bypassed.

## Reference and attribution

Contract references: Android Open Source Project, frameworks/base, source commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` (local android-17.0.0_r1 reference):

* core/java/android/os/IBinder.java: object identity, synchronous callback reentrancy,
  one-way ordering, death notifications.
* core/java/android/os/Binder.java and Parcel.java: caller identity and ownership.
* core/java/android/app/LoadedApk.java: ServiceDispatcher connection notifications.
* core/java/android/app/ActivityThread.java: service lifecycle on the main thread.

AOSP code is Apache-2.0 licensed. This patch uses these as behavioral references;
its new transport/control implementation is original ATL code (GPL-3.0-only), not
copied AOSP Binder native code. Existing AOSP headers in touched files are retained.
Android's Binder driver/AMS cannot simply be copied into a GTK userspace launcher.

## Reproducible tests

Inside the configured Alpine development container:

```sh
meson compile -C build -j3
sh tests/remote-services/build.sh build /absolute/path/to/probe-build
sh tests/remote-services/run.sh build /absolute/path/to/probe-build/remote-services.apk /absolute/path/to/test-output
```

The synthetic APK contains no account/private data and deliberately exits its worker
process. It checks real PID separation, process name/caller PID, local-interface
absence, lifecycle sharing, nested callback thread identity, Binder roundtrip identity,
FD transfer both directions and lifetime after recycle, one-way order, remote errors,
Binder death, ServiceConnection disconnection, calls to a dead peer, and explicit
rebinding into a new process.

Run the existing local-service-probe and mobile-smoke against the candidate too;
compare local-service events with a baseline. The new test is not a substitute for
Android-versus-ATL contract comparison or ARM/Nura verification.

## Verification record (2026-10-12, x86_64 Alpine Toolbox)

* Synthetic remote-services APK: separate PIDs, process/caller identity and process
  enumeration, descriptor queries and unknown transaction result, clear/restore
  caller identity, two clients, start/stop while bound, destroy/recreate after last
  unbind, null binding, nested synchronous callbacks on the waiting thread, ordered
  one-way calls, FD lifetime on both sides, runtime exception propagation, death
  during a pending call, death-recipient removal and service disconnection: passed.
* Existing local-service-probe v5: **all 16 mandatory scenarios, 384 events match**
  the baseline (Binder IDs and event sequence numbers normalized by the existing
  fail-closed analyzer). Baseline runtime: build-revanced from the main integration
  checkout, before these changes. This proves no change against that baseline;
  it is not a claim that the baseline implements all Android service contracts.
* Existing mobile-smoke: passed, including media/document FD handling, text/input,
  graphics, resources, cookies, signatures and the other existing synthetic checks.
* microG 252432034, private profile with no Google account and a synthetic challenge:
  previously failed in requireLocalService; now creates DroidGuardService in a child,
  completes broker setup/callbacks/handle RPC, and the Task completes. This is **not a
  successful DroidGuard attestation**. microG reports local DroidGuard unavailable in
  that test profile and its own fallback is not implemented. ServiceManager.sCache
  reflection also warns. No returned token is inspected or published.
* No Signal launch, real-account login, ARM/Nura run, distributed-reference lifetime
  stress test, malformed-frame fuzzing, or fresh Android-emulator comparison in this
  milestone. Those are unverified, not passed.

Before enabling by default or merging as general service support: finish handle
release/reference lifetime, review failure/cancellation and ownership under stress,
compare the remote contracts on Android, and verify an ARM build. Keep the opt-in
flag and these explicit limitations until then.

## Parent-provider reads

The same connection now exposes a parent-side provider endpoint. After service
process initialization, a child can query providers declared in its own APK
that belong to the parent application process. Providers remain instantiated in
the owning process, including those declared with multiple authority aliases.
This does not route to another app, sibling process, or another launcher.

Supported: legacy and Bundle query arguments, `getType`, null/empty results,
column names, null/integer/float/string/blob values, cursor extras, and provider
exceptions. Results are immutable snapshots capped at 512 rows, 128 columns,
and 64 KiB; oversized results fail explicitly, never truncate. Parent cursors
are closed on success and failure; snapshots allocate no persistent Binder
handles. `ContentProviderClient.getLocalContentProvider()` returns null for a
remote provider. Unknown authorities remain unavailable.

Mutations and file operations explicitly throw UnsupportedOperationException.
Snapshots do not implement live notifications or remote `respond`; `requery`
returns false. Cancellation is checked before dispatch and after the reply;
it does not interrupt a running parent query. There is no paged CursorWindow
transport, startup-time provider channel, cross-package permission/grant model,
or system-wide provider discovery. These limits are intentional first-milestone
boundaries, not full ContentProvider compatibility.

Reference: AOSP `ContentProviderNative.java` and `ContentProvider.java`,
android-17.0.0_r1 (94b4c163b7dfe5ce3607f7bb8456f9573f7de57d), notably provider
execution on Binder threads, query arguments, result ownership, and exceptions.
The ATL wire protocol/snapshot implementation is new GPL-3.0-only code; no AOSP
implementation was copied. Existing AOSP file headers are preserved.

`tests/remote-services` now exercises real child-to-parent reads including
process-local state, Binder calling PID, nested callbacks, cursor values and
extras, aliases, null versus empty, provider exceptions, oversized results,
cursor cleanup, cancellation before dispatch, rejected mutation, and authority
confinement. The same probe fails against the earlier build at remote-provider
acquisition. Optional `ATL_PROVIDER_DIAGNOSTICS=1` logs only null/completed status
and row counts, never query arguments, URIs, settings values, or cursor contents.
