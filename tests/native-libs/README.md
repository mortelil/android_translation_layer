# Companion APK native libraries

Inside atl_dev: `sh tests/native-libs/run.sh` runs the extraction contract suite
on OpenJDK (128 checks), including independent JVM publishers and immutable
loader generations. `sh tests/native-libs/run-art.sh` uses ATL_BUILD_DIR,
ATL_PREFIX and ATL_CORE_JAR to test the compiled candidate in ART.

The loader uses publishOrFail: the returned path identifies a complete immutable
APK/ABI generation. Extraction and validation finish in a private directory;
a same-filesystem directory rename publishes the set. Updates do not mutate or
remove generations that another process may still use. Corrupt existing
generations fail explicitly rather than overwriting live files. APK changes
during extraction are detected by comparing SHA-256 before/after extraction.
Old generations remain on disk; reclamation requires a separate lifecycle-aware
cleanup policy. The lower-level publish method is only a staging helper and
must not be used for library directories already exposed to an application.

OpenJDK tests cover damaged input, extraction/reuse, ABI selection, empty APKs,
replacement, stale entries, interrupted extraction, concurrency and preservation
of old readers on update/failure. The ART smoke covers real file locks, hashing,
rename and generation isolation. It halts after assertions to avoid the known
standalone ART teardown failure, not to suppress test failures.
