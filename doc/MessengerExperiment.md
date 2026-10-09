# Messenger experiment: initial x86_64 investigation

Status on 2026-10-09: **no usable Messenger UI verified**. This experiment is on
the local `messenger-compatibility` branches in ATL and bionic_translation. It is
separate from the published Plexamp milestone, ATL `66c1d0fe` / Bionic `3ab0a32`.
The dependency pin on this branch requires the local Bionic experiment commit.

Test input: `com.facebook.orca` 558.0.0.21.77, version code 341609208, x86_64,
minimum API 28. The available ARM64 APK is a different version (581.0.0.49.91);
it has not been tested in this pass. APKs and extracted libraries are not included.

Three runs used Alpine and a separate Xvfb display, private appdata and cache.
No Facebook account, credentials or messages were used.

1. Application initialization called the missing instance method
   `ActivityThread.getProcessName()`. It now delegates to ATL's existing process
   name implementation, consistent with `currentProcessName()` and Application.
   ATL still uses the primary package name, not an Android multiprocess model.
2. The next run reached native library loading, where `libsuperpack-jni.so`
   required `__fread_chk`. Bionic commit `9d01b3a` implements bounds-checked reads
   and overflow handling. Its tests pass for exact buffer limits with sentinels,
   EOF, partial items, zero size, multiplication overflow and fatal bounds errors.
3. The next run reaches the missing cross-DSO CFI runtime. `libc++_shared.so`
   imports `__cfi_slowpath` and exports both `__cfi_check` and
   `__wrap___cfi_slowpath`. `libsuperpack-jni.so` imports the latter wrapper and
   exports its own `__cfi_check`. Thus the wrapper's load failure is downstream
   of the absent CFI runtime, not evidence that an empty wrapper is appropriate.

The next step is a loader-aware CFI implementation with isolated native tests.
It must dispatch to the target instrumented module's `__cfi_check`, preserve
type IDs and diagnostic data, handle known non-instrumented modules according
to the ABI, and reject pointers outside known modules. Loading/unloading,
dependency lookup, host-library interaction and concurrent access need tests.
See the [LLVM cross-DSO CFI design](https://clang.llvm.org/docs/ControlFlowIntegrityDesign.html#shared-library-support).
An unconditional return from the missing function would disable the checks
and is not a compatibility implementation. No such bypass was added.

These changes were authored with AI assistance. No Messenger implementation,
decompiler output or new AOSP source was copied into this repository. This
investigation is not a compatibility claim or an upstream-ready submission.
