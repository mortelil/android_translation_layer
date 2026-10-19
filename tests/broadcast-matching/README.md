# Local broadcast matching

Run in atl_dev with ATL_BUILD_DIR/ATL_PREFIX/ATL_CORE_JAR:

    sh tests/broadcast-matching/run.sh /tmp/broadcast-matching

Sign the resulting APK for Android and launch
org.atl.tests.broadcast/.BroadcastActivity; require the fresh BroadcastProbe
marker BROADCAST_PASS deliveries=8. The test checks the complete delivery map,
including zero deliveries for rejected requests, rather than merely counting
successful callbacks. It covers duplicated manifest filters, disabled/explicit
components, unknown package, MIME and categories, two dynamic receivers sharing
a filter, duplicate registration, filter snapshot isolation, content-URI MIME
lookup and self-unregistration.

Completes the broadcast portion of OMP 9209229d, with full matching and fixes to
duplicate delivery and registration ownership. Intent.resolveType follows AOSP
frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d Intent.java.

Scope remains ATL's synchronous, in-process broadcast delivery. This does not
implement Android's asynchronous broadcast queue, receiver process startup,
ordered/sticky broadcasts, permissions/export policy or scheduler handlers.
Those existing limits are not represented as completed Android support.
