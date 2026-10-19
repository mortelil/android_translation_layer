# Manifest data and provider resolution probe

Run inside atl_dev with explicit ATL_PREFIX, ATL_CORE_JAR and ATL_BUILD_DIR:

    dbus-run-session -- xvfb-run -a sh tests/intent-resolution/run.sh /tmp/resolution-probe

The two APKs use only synthetic providers and private data. No network, account
or user files are accessed. The caller has an explicit package-visibility query
for the installed owner. The runner builds both APKs, installs the owner into
its private root and launches the caller.

For Android parity, sign the generated owner.apk and probe.apk with a test key,
install them in an emulator, then launch:

    adb shell am start -n org.atl.resolutionprobe/org.atl.resolution.Probe
    adb logcat -d -s ResolutionProbe

Require a fresh RESOLUTION_PASS checks=56 line and no RESOLUTION_FAIL.
The activity calls finish before exiting to prevent Android from restarting
an unfinished test activity. Keep failed output as evidence when investigating.

Covers parsed MIME/host/port/path/SSP constraints, positive and negative queries,
priority and duplicate suppression, GET_RESOLVED_FILTER, MATCH_DEFAULT_ONLY, disabled components,
unknown packages, explicit components bypassing filters, selector semantics,
local content-provider MIME resolution, and another installed/visible package.
A crucial Android detail: MIME is resolved on the original Intent before the
server substitutes its selector. A selector's MIME alone does not supply the
resolved MIME. Both cases are tested.

References: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d:
- core/java/android/app/ApplicationPackageManager.java:
  queryIntentContentProvidersAsUser (resolve type before IPC)
- services/core/java/com/android/server/pm/ResolveIntentHelper.java:
  queryIntentContentProvidersInternal (selector and explicit component handling)
- services/core/java/com/android/server/pm/resolution/ComponentResolver.java:
  ProviderIntentResolver (filters, disabled state, returned fields and sorting)
- core/java/android/content/pm/PackageParser.java:
  parseIntent and parseProviderTags

Based on OMP 8bfa7756, 026ce779 and the PackageParser portion of 9209229d.
The missing provider intent-filter parse branch is added; OMP's original
resolver would otherwise always see empty provider filter lists.

Scope: single ATL user and existing package-visibility policy; no Android
cross-user, instant-app or Direct Boot policy implementation. Local content MIME
resolution uses ATL's existing ContentResolver, not a new remote MIME RPC.
Advanced/suffix path/SSP patterns and relative URI filter groups are not covered.
Activity/service lookup and actual activity/service dispatch are separate work.
The query does not create/acquire providers (except MIME lookup when requested)
and does not replace permission checks at acquisition.
