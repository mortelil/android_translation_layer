# Activity picture-in-picture state query

This is only the negative state query for ATL, which cannot enter PiP. It does
not advertise PiP support or implement enter/exit, parameters or callbacks.
AOSP Activity.isInPictureInPictureMode reads mIsInPictureInPictureMode; absence
of parameters is not the criterion. Reference: frameworks/base Activity.java,
android-16.0.0_r1. No AOSP implementation is copied.

Build with `tests/activity-pip-query/build.sh "$ANDROID_JAR" /absolute/output`
inside Alpine, sign with the host SDK, and use the same signed APK on Android
and ATL. The activity must report false in both onCreate and onResume. Require
`PIP_QUERY_PASS create,resume` and no `PIP_QUERY_FAIL` in a fresh log. An unresolved
method is a failure, not an unsupported/skipped test. Use explicit ATL_BUILD_DIR
and isolated appdata as described in ../paint-measure/README.md.

Android component: org.atl.tests.pipquery/.ProbeActivity.
