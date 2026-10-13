# ART runtime statistics

Build in Alpine with `sh tests/runtime-stats/build.sh BUILD_DIR OUTPUT_DIR`.
Sign with a local test key; launch org.atl.runtimestats/.Probe and require
RUNTIME_STATS PASS (files/results.json). Same probe can run on Android.
Checks real allocated bytes, nonnegative/monotonic GC count, unknown/null names
and independent maps. Does not claim an exact GC scheduling policy.

Implementation from OMP OsmAnd 578ff85 delegates to existing AOSP ART VMDebug
statistics rather than fabricating counters. Contract reference: the checked
ART libcore/dalvik/src/main/java/dalvik/system/VMDebug.java implementation.
