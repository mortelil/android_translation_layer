# Headless fragment lifecycle contract

Adds a framework Fragment after the Activity has resumed, checks attach/create/
activity-created/start/resume ordering, duplicate suppression on empty dispatch,
pause/stop/destroy/detach on removal, cleared host/tag lookup, re-add, and committing
from a worker with execution on the main thread. A fresh results.json is written
only after all assertions pass.

Reference: AOSP frameworks/base android-17.0.0_r1, commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, FragmentManager enqueueAction,
ensureExecReady and moveToState. Existing source copyright/license preserved.
The same APK passes on Android API 37 and ATL. Before the fix ATL omitted start
and resume for late additions. This does not implement view fragments, saved
state, nested managers or a back stack.

Build inside Alpine using R8_JAR=/path/to/r8.jar sh tests/fragment-lifecycle/build.sh
BUILD_DIR OUTPUT_DIR. Sign with a test key; launch org.atl.fragmentlife/.Probe.
Require FRAGMENT_LIFECYCLE PASS and a fresh files/results.json, not just process
startup. Android reference: install the same APK, start that activity, inspect
System.out. Never use personal app profiles for this test.
