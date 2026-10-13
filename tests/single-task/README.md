# Existing singleTask activity

Launch `org.atl.singletask/.Probe` after signing the APK built by build.sh.
The Activity relaunches itself through its application context. Assert one
instance, asynchronous delivery with extras, pause before onNewIntent, resume
afterward, and no second start/create. Require SINGLE_TASK PASS and a fresh
files/results.json. The same APK passes on Android API 37 and ATL.

The test also sends an unknown audio hardware parameter. AOSP AudioManager's
void setParameters discards the underlying backend error. ATL reports that
hardware parameters are unsupported in its log; it neither stores fake values
nor advertises device control. This is not audio focus or routing support.

References: Android Activity.onNewIntent documentation; AOSP frameworks/base
android-15.0.0_r1 (4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3) AudioManager.java.
The singleTask change reuses ATL's existing single-window stack. Multi-task
routing/affinity and complete singleTop/CLEAR_TOP semantics remain outside this
patch. Regression fixture: VLC 3.7.1 opens a video with NEW_TASK and its manifest
singleTask entry; previously it recreated the player repeatedly.
