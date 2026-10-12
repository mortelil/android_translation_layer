# Explicit package visibility and ReVanced

The manifest parser previously discarded `<queries>`, while the app loader's
visibility policy allowed only hardcoded Play-services packages. Consequently
an application could not find a separately installed, explicitly queried package.

The parser now records `<queries><package android:name="..."/></queries>`.
Direct package lookup permits declared package names, pre-30 target SDK callers
and callers declaring QUERY_ALL_PACKAGES. Existing automatic visibility remains.
Visibility is checked before consulting cached package metadata. Declarations
do not fabricate installed packages or replace signing certificates.

This is not the full Android package-visibility system. Intent/provider queries,
interaction-based automatic visibility and complete installed-package/component
enumeration remain outside this patch. Unsupported query children are reported.
The policy is for ATL's primary application, not a new Binder caller-identity or
cross-process security model. Existing GMS-specific signature behavior is not
expanded to ReVanced packages.

References: Android's [package visibility](https://developer.android.com/training/package-visibility)
and [declaration rules](https://developer.android.com/training/package-visibility/declaring).

## Tests

`tests/package-queries/build.sh BUILD OUT` builds a probe and two manifest-only
fixture APKs. Register `org.atl.queryfixture.apk` and `org.atl.hiddenfixture.apk`
in a **private** profile's `_installed_apks_` directory, then run
`package-queries.apk` in that profile. Expected marker:
`ATL PACKAGE QUERY CONTRACTS PASSED`, exit 0. The fixtures are unsigned and
test metadata lookup only, not installation signature validation.

The real manifest/PackageManager test checks declared visibility, undeclared
denial, declared-but-absent denial, target SDK 29/30 behavior, QUERY_ALL_PACKAGES
and rejection even after metadata was cached while visible. The probe temporarily
changes its own parsed SDK/permission model to exercise those policy boundaries;
it does not change actual user applications or permissions. Full mobile-smoke
and the WebView regression probes also passed on Alpine x86_64.

## Installing the required package in the launch profile

Patched YouTube 20.40.45 requests `app.revanced.android.gms`. The separate
microG package `com.google.android.gms` does not satisfy that dependency. Use
the real [ReVanced GmsCore release](https://github.com/ReVanced/GmsCore/releases),
not a renamed APK or a package/signature override.

Register the APK with `--install-internal` using the **same
ANDROID_APP_DATA_DIR and runtime** as the YouTube launch. A copy in a private
test profile is not visible to the user's normal profile. For the repository's
mobile launcher, run inside the configured Alpine environment:

```sh
export ANDROID_APP_DATA_DIR=/absolute/path/to/your/atl-profile
# Keep ATL_PREFIX, ATL_BUILD_DIR and ATL_CORE_JAR set as for normal launches.
sh scripts/mobile/run.sh /absolute/path/to/revanced-gmscore.apk --install-internal
sh scripts/mobile/run.sh /absolute/path/to/patched-youtube.apk
```

A wrapper that forwards ATL options can append `--install-internal` in the same
way. Registration should create
`$ANDROID_APP_DATA_DIR/_installed_apks_/app.revanced.android.gms.apk`.
Verify that file exists and matches the downloaded APK: the current installer
can print a copy error without returning a nonzero status. Installation does
not transfer a Google account from ordinary microG. Do not copy credentials
between the two variants.

If the log says `GmsCore was not found`, displays an installation toast, opens
the release page and exits with status 0, this is the app's deliberate missing-
dependency exit, not an ATL native crash. Fix the package/profile mismatch first.

## Observed compatibility, 2026-10-12

The tested default signed ReVanced GmsCore v0.3.13.2.250932 APK has SHA256
`62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65`, verified
against its release asset digest. Other versions have not been checked here.

After registration, ReVanced finds and loads the package. Subsequent fixes on
main address cache-based Conscrypt loading, fragment callback ordering and
creation, undefined system night mode, theme overrides and typed Parcel markers.
The latest isolated run still terminates at missing `android.app.DialogFragment`;
WolfSSL certificate-chain validation and GmsCore API availability warnings also
remain. Neither login nor playback is verified. No tracing/signature/TLS check
was bypassed, and app bytecode was not modified.
