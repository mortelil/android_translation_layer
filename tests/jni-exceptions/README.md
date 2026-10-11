# JNI exception boundaries

The pinned ART (`art_standalone` 327b9c2e, `art/runtime/jni/jni_internal.cc`,
`ExceptionDescribe`) restores the original pending exception after printing.
Printing and then continuing ordinary JNI work is therefore invalid under
`-Xcheck:jni`. This patch stops failed callback paths immediately.

Internal Activity helpers propagate the pending exception unchanged. Java
`nativeStartActivity`, `nativeResumeActivity` and `nativeFinish` callers can
catch it. Only the outer GTK startup/window/file callbacks and GLib Looper
boundary dispatch an uncaught exception. `prepare_main_looper` is called only
from native startup. No helper is installed in Java-owned `nativePollOnce`.

The uncaught helper retains the rooted **local** Throwable reference across
`ExceptionClear`; no additional global allocation is necessary. It dispatches
that identical object through `Thread.dispatchUncaughtException`. The pinned
core's default handler prints and calls `System.exit(10)`. If a custom handler
returns, ATL exits(1), rather than resuming a failed main-thread callback. If
routing throws, ATL reports the original and secondary exceptions then exits(1).
This is ATL's main-loop termination policy, not a claim that ART terminates the
process for every uncaught exception on every thread.

The fallback uses method IDs resolved on `java.lang.Throwable` (valid for both
subclasses and differently typed causes), checks lookup/UTF allocation failures,
and traverses at most 16 cause nodes. It does not reconstruct stack frames or
suppressed exceptions. Throwing accessors truncate diagnostics; arbitrary Java
handlers/accessors that block cannot be made time-bounded by this helper.
This is not a general audit/fix of all ATL JNI call sites or lifecycle rollback.

## Tests

| Case | Contract |
|---|---|
| A | Startup throws with cause; original diagnostic, no later callback or CheckJNI abort; verified terminal path. |
| B | Throwing formatter; handler witnesses original object identity; fallback type/message/cause and secondary failure survive. |
| C | Java callback catches locally; normal startup completes. |
| D | Java catches the identical object across actual nativeStartActivity and nativeResumeActivity; later Handler executes. ATL-specific entrypoints, invoked reflectively to compile against public SDK. |
| E | Custom startup handler receives identical object and current thread; returning handler does not resume failed callback. |
| F | Uncaught Handler exception reaches GLib Looper boundary; identical object, queued sentinel does not execute. |
| G | Throwing handler and self-referential cause; bounded 16-node diagnostic, no JNI abort. |

B's `original_present=false` remains the older literal-class diagnostic: the
fallback prints the subclass, not `java.lang.IllegalStateException`. New
`identity_witnessed` is separate evidence, not a relabeling of a full stack trace.

`helper-test.c` injects failed lookup, throwing accessors, cyclic causes and UTF
allocation failure into a JNI table which rejects ordinary calls while pending.
It also tests no-op without an exception. This supplements real CheckJNI probes;
it is not a substitute for them.

## Running

Use the existing Toolbox `atl_dev`, host SDK and probe runner; no runtime install.
Set `FRAMEWORK_RES` to the candidate build's framework-res.apk and `ANDROID_JAR`
if the existing SDK differs from the default. Build in the container:

```sh
JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac tests/jni-exceptions/build.sh OUTPUT
cc -I/usr/lib/jvm/java-21-openjdk/include -I/usr/lib/jvm/java-21-openjdk/include/linux \
  tests/jni-exceptions/helper-test.c -o /tmp/jni-helper-test
/tmp/jni-helper-test
```

On the host, sign and run each A–G case with explicit candidate paths:

```sh
tests/jni-exceptions/sign-apks.sh OUTPUT FROZEN_APKS
ATL_PROBE_SOURCE_DIR=CHECKOUT ATL_PROBE_BUILD_DIR=CHECKOUT/build-mobile \
  JNIEXC_APK_DIR=FROZEN_APKS JNIEXC_RUNNER=EXISTING_RUNNER \
  tests/jni-exceptions/run-case.sh A FRESH_RESULT_DIRECTORY
tests/jni-exceptions/run_analyzer_selftests.sh
```

APKs use anonymous classes because the pinned runtime's invokedynamic lambda
bootstrap is not functional. No unrelated runtime repair is included.

The analyzer rejects missing evidence, wrong run IDs, absent JVM startup,
secondary JNI failures, continued callbacks and missing terminal diagnostics.
Terminal A/B/E/F/G need not write results.json; a generic runner exit1 alone is
never success. C/D require their completion markers. `runtime-sha256.txt` hashes
the launcher, implementation library and API jar, not merely the launcher.
Corroborate origin with runner maps and ART's logged classpath; the JVM startup
line alone is not independent proof of CheckJNI. ATL's `-Xcheck:jni` option and
an identical-APK failing baseline provide that evidence in this review.

The analyzer fixture suite includes formerly accepted empty B diagnostics and
nonterminal A logs as negative controls. Run directories must be fresh.
