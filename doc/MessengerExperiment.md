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

Latest run (`startup-patchable-20.log`) passes these native startup blockers but
stops with `NoSuchMethodError: Typeface.Builder(File)` on CombinedTP10. Existing
ATL Typeface/Builder and Paint font selection largely return placeholder values;
proper file-backed font loading/rendering is the next task, not another fake
successful builder. Caught sensor-listener and TrafficStats query errors also
remain. A screenshot before the fatal error shows a blank window only. No usable
Messenger UI or Nura support has been verified.

The full PC automated regression suite passes after the loader and ART fixes
(`runpath-full-regression.log`). This does not replace interactive regression
checks of the four previously working applications. No changes from this experiment
have been published or installed on Nura. Debugger runs also establish that `STI`
instructions installed by the app are deliberate signal hooks; debugging must
let it handle those traps.

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
