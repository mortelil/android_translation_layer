# ATL acceptance gate v1 (REVIEW-REPAIRS)

Versioned description of the executable gate. The shared
`agent-work/quality-gates.md` points here. A written rule is not a substitute for
the executable test: `tests/acceptance.sh` runs the real commands below.

## Entry point

```
tests/acceptance.sh [--workdir DIR] [--manifest FILE]
tests/acceptance.sh --stage {build|identity|probe|contract|selftests|regression}
tests/acceptance.sh --record-manifest [FILE]
```

Exit 0 **only** for `ACCEPTANCE VERDICT: READY_FOR_REVIEW`. Runs on the **host**
(needs `toolbox`); ATL build/probes execute in container `atl_dev`. Working dir
recorded per run; default `/tmp/atl-acceptance/run-<timestamp>`.

Identity manifest: `tests/acceptance/artifacts.manifest` — sha256 of
`build-mobile/{libtranslation_layer_main.so,api-impl.jar,android-translation-layer}`,
source commit + tracked-dirty digest, the five frozen APKs and the five
verifiers/selftest inputs. Refresh with `--record-manifest` after a deliberate
change; a mismatch fails closed. Committing the gate files themselves advances
HEAD without changing the implementation: the source-commit check accepts HEAD
moved by `tests/`-only commits and still fails on any non-`tests/` source change.

## Stages / verified command paths

- **build** runs `meson compile -C build-mobile -j2` in the selected checkout inside `atl_dev`. Configure this isolated build first; the gate does not rebuild/install shared dependencies.
- **identity** sha256 of the loaded `.so`/`api-impl.jar`/launcher + `git rev-parse HEAD`
  + dirty digest, compared to the manifest.
- **probe** `ATL_PROBE_SOURCE_DIR=/home/morten/ATL/review-repairs ATL_PROBE_BUILD_DIR=/home/morten/ATL/review-repairs/build-mobile /home/morten/ATL/tools/run-atl-probe.sh <APK> <OUT> <MARKER> 300`
  for FONT-003/004/006/007 (`FONT0NN_JSON_END`) and the lifecycle v2.1 APK
  (`TERMINAL_THROW_DONE`). Completion = marker, never process exit.
- **contract**
  - `python3 tests/font-probe/verify_font_metrics.py <android-ref> <cand> --allow-bare`
  - `python3 tests/font-probe-bounds/verify_font_bounds.py <android-ref> <cand> --allow-bare`
  - `python3 tests/font-probe-glyph/verify_font_glyph.py <android-ref> <cand> --allow-bare`
  - `python3 tests/font-probe-gb/verify_font_gb.py <android-ref> <cand> --allow-bare`
  - `python3 tests/lifecycle-probe/verify_lifecycle.py <android-trace> <cand-trace> --baseline=<red-atl-trace>`
- **selftests** `tests/lifecycle-probe/run_analyzer_selftests.sh` +
  `tests/font-probe*/run_font_*_selftests.sh` (negative controls; an environment
  block can never become a PASS).
- **regression** `tests/mobile-smoke` inside `atl_dev` after
  `. $HOME/ATL/test-env.sh` + `. scripts/mobile/environment.sh`; success =
  `ATL TEST APK PASSED`.

## Verdict model (kept separate; conjunctive only for the final verdict)

`artifact identity`, `build`, `evidence validity`, `contract verdicts`,
`negative control`, `regression`. `READY_FOR_REVIEW` iff all six PASS. FAIL
CLOSED: missing mandatory input/tool, empty/unparseable/stale evidence, wrong
APK/artifact/comparator hash, or a skipped/failed required check ⇒ non-zero exit.
`--stage` runs are diagnostic and never grant `READY_FOR_REVIEW`.

## Integration builds

The regression stage sources the shared dependency environment **before** selecting
`ATL_BUILD_DIR` and `atl_repo`. Otherwise test-env.sh can silently overwrite the
candidate path. The probe runner must likewise select the candidate and use fresh
app data/cache. Capture process maps (including the api-impl dex cache path) and
SHA-256 of the mapped library, launcher, JAR, dependencies and APKs; a recorded
manifest alone does not establish that a running process loaded those files.

Keep separate stage verdicts when using staged runs. In particular, the FONT-007
bitmap structural check is not Android parity, and lifecycle baseline-pinned
divergences are not newly implemented Android ordering.
