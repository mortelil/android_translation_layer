# Messenger experiment: initial x86_64 investigation

Status on 2026-10-09: **no usable Messenger UI verified**. This experiment is on
the local `messenger-compatibility` branches in ATL and bionic_translation. It is
separate from the published Plexamp milestone, ATL `66c1d0fe` / Bionic `3ab0a32`.
The dependency pin on this branch requires the local Bionic experiment commit.

Test input: `com.facebook.orca` 558.0.0.21.77, version code 341609208, x86_64,
minimum API 28. The available ARM64 APK is a different version (581.0.0.49.91);
it has not been tested in this pass. APKs and extracted libraries are not included.

Tests run inside Alpine on independent Xvfb displays with private appdata/cache.
No Facebook account, credentials or messages were used. The following describes
startup progress, not working Messenger support.

1. `ActivityThread.getProcessName()` now delegates to ATL's existing process
   name implementation. This does not add Android's multiprocess model.
2. Bionic implements fortified `__fread_chk` and `__memchr_chk`, with buffer
   bounds checks, overflow handling and positive/negative native tests.
3. Cross-DSO CFI dispatches to the target instrumented module's `__cfi_check`,
   preserves the type ID and diagnostic data, allows known uninstrumented
   modules and rejects pointers outside loaded mappings. Tests include actual
   Clang instrumentation, invalid signatures, unload/reload, GNU/SysV hash
   tables and host executable targets. This is a slow lookup implementation,
   not LLVM's shadow-memory acceleration or a security audit.
4. C++ thread-exit callbacks retain their Android DSOs until destruction and
   preserve Android callback LIFO ordering, including newly registered callbacks.
   Worker-thread and process-exit tests pass. Mixed host/Android ordering and
   concurrent unload stress still need broader testing.
5. Resources stores its actual application classloader in `mClassLoader` and
   exposes `getClassLoader()`. Smoke tests verify application class identity.
6. Native SDK properties now agree with `Build.VERSION.SDK_INT` and Java
   SystemProperties. ATL calls a checked Bionic setter after Java initialization.
   This shares the existing selected SDK policy; it does not claim that all
   APIs at that level have been implemented.
7. Native `mallinfo` can return real jemalloc statistics when jemalloc is the
   active process allocator. The backend is opt-in using process-local
   `LD_PRELOAD=/usr/lib/libjemalloc.so.2`; unsupported backends fail explicitly.
   Its standalone test verifies an 8 MiB allocation and release. Unsupported
   legacy counters remain zero; see the Bionic test documentation for semantics.

Full PC ATL regression tests pass, including a run with jemalloc preloaded.
The native CFI tests also pass after replacing repeated host dlopen/dlclose with
ELF lookup inside loader enumeration. This avoids debugger library events on
every JNI CFI check. ARM64 has not been validated for these new changes.

The main startup blocker was traced beyond its ART crash site. Allocation tracing
shows a double free inside the APK's Breakpad `distract_hook` failure path. It
happens when Dextricks attempts `ART_HACK_DEX_PC_LINENUM`, targeting the host
ART `art::annotations::GetLineNumFromPC` implementation. The freed block later
appears twice in jemalloc's cache and is assigned to both an ART hash table and
a vector. Disabling the thread cache only changes the symptom; it is not a fix.
Matching Alpine ART runtime/compiler debug symbols (0_git20251009-r2), hardware
breakpoints and allocation traces establish this sequence. Ordinary software
breakpoints interfere with these self-modifying native hooks.

A separate source build using GCC's `-fpatchable-function-entry=16` now gets
Messenger past the failing hook and ensuing memory corruption. It reserves real
instrumentation space while retaining function behavior. The full PC test suite
passes with this runtime, including repeated and concurrent Java stack captures
that check source filenames and adjacent line numbers. This remains an isolated
experimental runtime, not a published build option or proof of app compatibility.
See GCC's instrumentation-option documentation.

Later startup needed the actual APK path in `ApplicationInfo.publicSourceDir`,
which now matches `sourceDir` for the loaded, readable APK. The smoke test checks
the path and file existence. Messenger's LoadDexes stage now completes.
Bionic also implements `__sendto_chk`, `__recvfrom_chk` and `__getcwd_chk` with
real bounds checks and host calls. Tests use local datagram sockets (binary data,
peek, truncation, zero length and errno), current-directory path checks and
subprocesses that must abort on buffer overflows. These are native API fixes,
not success-returning substitutes for network or filesystem operations.

Telephony startup probes now report unavailable cell data and an invalid default
data subscription, because ATL has no radio/subscription backend. This adds the
missing query methods; it does not implement telephony or collect radio data.
Bionic now also resolves `pthread_gettid_np` using Linux per-thread CPU clock
IDs, with tests comparing the result against kernel TIDs for live host and
Android-created threads. See the Bionic thread-ID test notes for lifetime limits.

The unavailable values follow the [TelephonyManager API](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo())
and [SubscriptionManager API](https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultDataSubscriptionId()).

A second independently observed loader failure, `ASharedMemory_create`, now has
a Linux memfd implementation plus `ASharedMemory_getSize`. Native tests verify
cross-process contents, descriptor/mapping lifetime and immutable region size.
The full PC regression suite passes after this change. Protection reduction
and Java SharedMemory transport are not implemented or advertised.

The later jemalloc crash was traced with a separately built debug allocator to
`Thread::TearDownAlternateSignalStack`: ART deleted the kernel's current signal
stack after the application replaced it with independently allocated storage.
ART now retains its own allocation and restores a prior stack only while its own
stack remains active. It leaves application replacements untouched. Real signal
delivery and stack replacement tests, plus the full PC ATL suite, pass. See
ART's `tests/mobile/signal-stack.md`. This source change is currently used only
in the isolated source-built ART runtime; the ordinary mobile build script does
not yet build or deploy libart.

The next C++ unwinder crash was traced to ELF enumeration reporting program
headers from a failed, already-unmapped library. Bionic now enumerates only linked
mappings under its loader lock and advertises only the implemented info prefix.
A standalone fixture reproduces the old failure and checks concurrent load/unload,
readable headers and early-stop propagation after the fix.

The underlying library failure was a direct dependency using `DT_RUNPATH` with
`$ORIGIN`. Bionic now resolves this relative to the requesting object, without
adding a global search path. Tests cover both ORIGIN spellings, colon-separated
paths and explicit library-path priority. See Bionic's `tests/runpath/README.md`
for unsupported tokens and remaining loader limits.

The file-font blocker is fixed by real Fontconfig/Pango font loading; see
`FileFonts.md` and local commit `5a1b8bb9`. Later changes add view/autofill metadata,
GTK text selection/placeholder/shadow styling, and explicit rejection of unsupported
hardware key attestation before software key generation. GTK pixel tests caught
and corrected CSS that did not initially reach the placeholder/selection nodes.

The next fatal errors were missing APK signing metadata and incorrect local Binder
caller identity. Optional AOSP apksig verification now supplies real v2/v3 signer
certificates and SigningInfo (see `ApkSignatures.md`). Binder local calls return
the process's real PID/UID; loaded app metadata uses that UID. The former
`Process.myUid() == -1` workaround is removed. This is local-process behavior,
not Android sandboxing or remote Binder support; WhatsApp's earlier workaround
has not been revalidated. API contract: [Binder caller identity](https://developer.android.com/reference/android/os/Binder#getCallingPid()).

Run `startup-patchable-47.log` reached a visible Messenger login screen, captured
in `messenger-47-live.png`. A delayed background
call then failed on the missing `TrafficStats.getTotalRxBytes()` method. Total
traffic queries now explicitly report UNSUPPORTED because ATL has no persistent
since-boot accounting backend; they do not fabricate zero traffic. Local Binder interface lookup now returns the attached owner, avoiding a proxy
transaction against an empty Parcel. Desired text width now includes supported
size spans, fixing incorrectly wrapped login labels. Tests demonstrate typing
and deleting a synthetic email address. Password input reaches the GTK buffer,
but its validation fails while loading libcore.so because OpenSL ES was absent.
Alpine libopensles-standalone (0_git20250913-r0) is now installed. The next
missing Bionic entry point, __system_property_read_callback, is implemented
using a consistent atomic SDK value/revision snapshot, with callback/reentry tests.
Run `startup-input56.log` uses both fixes and completes typing/deleting synthetic
email and password text without the earlier fatal errors. Password masking is
visible in `messenger-56-password.png`. No login was submitted. Some snapshots
still lose the background/unchanged content. The same issue occurs with Cairo
(run 57) and GSK_DEBUG=full-redraw (run 58), while a minimal standalone GTK
control keeps its background. This was still unresolved at run 58; the later composited-display tests described below supersede that diagnosis. Run 58 completes input/deletion
without fatal JNI errors, but that does not establish login or messaging support. No Messenger account or message exchange was tested in those runs; see the later Nura results below.

The full PC automated suite passes after the property callback, styled width,
signing metadata and local identity changes (`property-full-regression.log`), including the native GTK pixel
tests. Real signer/tamper tests pass (`styled-width-full-regression.log`); the actual
helper protocol also passes under ART at API 28 (`signing-art-protocol.log`). These tests do not
replace interactive regression checks of the four previously working applications.
At that stage nothing had been published or installed on Nura. Later tests use a private source-built runtime on the aarch64 Nura phone, without replacing its system packages. Debugger
runs establish that `STI` instructions installed by the app are deliberate signal
hooks; debugging must let the app handle those traps.

ResourcesImpl reflection warnings are caught by the app. Crash-report retries
also reach unsupported AndroidCAStore behavior; fresh isolated profiles separate
that recovery path from first-start failures. No such failure has been hidden
with a success-returning stub.

Local diagnostic logs and helper scripts are in `~/src/atl-messenger-logs/`;
these are not shipped. Relevant runs: `startup-gdb16.log` (ART locals),
`startup-gdb24.log` (double-free tracing), `startup-gdb31.log` (hook targets), `jemalloc-regression-tests.log`,
`cfi-hash-tests.log`, and `mallinfo-final-tests.log`.

ABI reference: [LLVM cross-DSO CFI design](https://clang.llvm.org/docs/ControlFlowIntegrityDesign.html#shared-library-support).

These changes were authored with AI assistance. No Messenger implementation,
decompiler output or new AOSP source was copied into this repository. This
investigation is not a compatibility claim or an upstream-ready submission.

## Follow-up verification (2026-10-09)

The login UI now works in isolated tests on both x86_64 Alpine and aarch64
Nura. On PC, compositing the private Xvfb display with xcompmgr fixes the
missing background without a renderer source patch. Synthetic email/password
insertion, deletion, masking, the password visibility toggle and language
chooser were exercised. On Nura, synthetic Unicode input, deletion, masking,
focus changes, empty-login focus and opening the language chooser were verified
with source-built ART. Physical on-screen keyboard input remains untested in
this verification round. Extra corner outlines around input fields remain.
No actual Messenger login was submitted.

Nura also needed `SubscriptionManager.getDefaultSmsSubscriptionId()`. It
returns `INVALID_SUBSCRIPTION_ID`, consistently with the lack of a telephony
backend. The API contract test asserts that unavailable result.

The full PC contract suite and the ARM APK contract suite passed. Native ARM
ART Looper and alternate-signal-stack tests passed. Bionic thread destructor,
ELF enumeration and RUNPATH fixtures passed on ARM. CFI tests now distinguish
AArch64 BRK/SIGTRAP from x86 UD2/SIGILL; loader rejection still requires
SIGABRT. Both handwritten hash-table variants and Clang-generated ARM CFI
fixtures passed. The latter fixtures were cross-compiled on PC because Nura
does not have Clang. This is not a claim that the entire ARM test script ran
without external preparation.

App regression validation is still incomplete. In particular, the available
Plexamp APK has only ARM native libraries, preventing an x86_64 PC test.
Plexamp's actual sign-in view and synthetic text editing passed on Nura with
the final source ART runtime. Signal's PC phone-registration page fails on
`Layout.getLineBottom(-1)`; the same failure reproduces with published ATL
66c1d0fe in the same runtime. Immich authenticated image viewing and navigation
were tested using explicitly authorized private appdata copies; backup was
disabled in the Nura copy before launch. No personal media was modified.
LocalSend transferred a disposable file in both directions with matching hashes;
the fallback GTK file chooser is too wide on the phone, and the default portal
flow has not been verified. Media playback and remaining input checks are not
yet fully approved. The private detailed matrix and screenshots stay outside
the repository.

The normal mobile build script still does not reproduce the source ART runtime
used here. PC also used D8-desugared core classes, jemalloc and ART compiled with
`-fpatchable-function-entry=16`. This deployment gap and the unfinished app
checks must be resolved before treating this branch as a validated release.
