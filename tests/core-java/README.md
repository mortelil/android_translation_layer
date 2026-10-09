# Java core lambda regression

`TestStreams` reproduces the failure in Alpine's packaged ART boot libraries:
`Collectors.toList()` raises a BootstrapMethodError caused by `illegal
lookupClass: class java.util.stream.Collectors`. The packaged DX-generated
Java libraries retain lambda call sites that this ART runtime cannot execute.

Build the replacement core library with `scripts/mobile/build-core-java.sh`.
Run this test with `ATL_CORE_JAR` pointing to the resulting core-all-hostdex.jar.
The build preserves resources from both original boot jars, including ICU data.
The replacement is opt-in; no system files are modified.

The test exits explicitly, like ATL's APK regression harness. It tests stream
execution, not dalvikvm teardown; a separate normal-return experiment exposed
an abort during JavaVM shutdown and is not covered here.
