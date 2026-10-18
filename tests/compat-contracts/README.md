# Small compatibility contracts

Run inside Alpine Toolbox, with `ATL_BUILD_DIR` pointing at this checkout's
build and `ATL_PREFIX` / `ATL_CORE_JAR` identifying the selected ART runtime:

    sh tests/compat-contracts/run.sh

The runner prints the compile/runtime artifact hashes. Tests cover Intent merge
and source isolation, forwarding every method of ATL's InputConnection interface
(including arguments and return values), target replacement rules, and unknown
Wi-Fi link metrics. They do not claim hardware Wi-Fi integration or GUI coverage.

`ATL_TEST_RUNTIME_JAR` can select a baseline dex jar while compiling against the
candidate. `ATL_TEST_ROWS=extras` (or input/wifi) selects a single counter-test.
All three rows fail against main 664a50a1 and pass against this candidate:
extras 10 checks, input 72 checks, wifi 2 checks.

The test explicitly halts after successful assertions to avoid the known
standalone ART shutdown crash. Assertion failures retain a nonzero exit; this
suite does not test VM shutdown or launch any user application.

Contract references: AOSP frameworks/base Intent.putExtras and
InputConnectionWrapper at android-17.0.0_r1, commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d; Wi-Fi unavailable metrics are documented
in the original imported commit. Source attribution is retained in commit history.
