# G-PATH `android.graphics.Path.op` probe

Frozen probe APK + fail-closed comparator for `Path.op` (both overloads, all
five ops). See `agent-work/APP-COMPAT/G-PATH/probe-report.txt` for the full
report (APK sha256, run commands, tolerances, reference results, limitations).

## Build (inside the `atl_dev` container, JDK-8 javac)

```sh
toolbox run --container atl_dev sh -lc \
  'JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
     /home/morten/ATL/app-compat/tests/path-probe/build.sh /abs/out'
# sign the resulting path-probe-aligned.apk on the HOST with apksigner
```

`ProbeActivity` emits a deterministic JSON block delimited on stdout by
`PATHPROBE_JSON_BEGIN` / `PATHPROBE_JSON_END` and writes it to
`files/results.json`. Completion = the `PATHPROBE_JSON_END` sentinel, never
process exit (ATL GUI apps do not self-exit).

Observation channels are geometry-derived (no rasteriser):
`computeBounds`, `Path.approximate(1.0)`, and `Region.setPath` membership over a
fixed 6000-sample integer grid (packed hex mask).

## Comparator

```sh
python3 compare.py <android.json> <atl.json> [--bounds-tol F] [--mask-tol N]
```

Exit 0 `OK`, 1 `REJECT <CODE> <reason>`, 2 internal. Fixed documented
tolerances; fail-closed.

Channel-aware: a channel the ATL side reports as explicitly unavailable
(structured `status:"error"` naming a missing/unsupported API such as
`NoSuchMethodError`, `AbstractMethodError`, `UnsupportedOperationException`, or a
missing framework native reported as `UnsatisfiedLinkError` that names the
native method/symbol, e.g. `Region.nativeSetRect` / `No implementation found
for ...`) is treated as NOT_PROVEN — excluded from the pass/fail decision and
named in the verdict notes (`NOT_PROVEN channel=... cases=... reason=...`). The
hard channels (ret, is_empty, fill_type, bounds, region mask, operand roles,
exception class) are never excluded. A channel that is missing, truncated,
malformed, or errors without such a marker is still a hard reject (a generic
`UnsatisfiedLinkError` with no native symbol does not count), and an
unsupported *android* reference is never tolerated.

## Self-tests

```sh
bash run_analyzer_selftests.sh    # 37/37 pass; mutations REJECT nonzero
```

Fixtures are regenerated from `fixtures/reference.json` (the frozen Android
reference) by `make_fixtures.py`.

## Exception-expected cases

Null-operand cases are marked `exception_expected: true` in the probe JSON. The
comparator requires the flag to match on both sides (else
`REJECT EXCEPTION_EXPECTATION_MISMATCH`) and, for such a case, compares only the
boolean return, the exception CLASS and the operand roles — never the
post-exception geometry (the receiver is untouched by the throwing call). The
pre-call receiver state is carried as `receiver_before` for evidence.

Curved-boundary operands are `moveTo`/`lineTo` polygons, not `addCircle`/
`addOval`/`addRoundRect` (whose ATL direction handling is a pre-existing
out-of-scope gap), so no case's `Path.op` verdict depends on operand
construction.

## Native bridge regression checks

Inside Alpine with `skiasharp` installed:

```sh
cc $(pkg-config --cflags gtk4) -Isrc/api-impl-jni/graphics \
  tests/path-probe/native-contract.c src/api-impl-jni/graphics/path_ops.c \
  -o /tmp/atl-path-contract $(pkg-config --libs gtk4) -lSkiaSharp -lm
/tmp/atl-path-contract
```

These compare 240,000 point memberships with the operand truth table for all
five operations and all fill-rule combinations, including a tiny contour next
to a distant contour, and require cubic output to retain curves. Unlike the
Android APK comparator, they do not depend on ATL's unimplemented Region or
Path.approximate methods. This is targeted coverage, not proof of all geometry.
SkiaSharp C headers are pinned under `third_party/skiasharp`; the native library
is a required dependency. Generated negative fixtures are not committed.
