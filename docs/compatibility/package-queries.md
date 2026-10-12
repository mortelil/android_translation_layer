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

## ReVanced result

The user's patched YouTube 20.40.45 requests `app.revanced.android.gms`, not the
`com.google.android.gms` APK used for the separate microG login test. Tested the
actual [ReVanced GmsCore v0.3.13.2.250932](https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.2.250932)
default signed APK, SHA256
`62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65`, matching the
release asset digest. It was installed only in a private test profile.

With that APK installed and this patch, ReVanced now finds and loads
`app.revanced.android.gms`; its previous "GmsCore was not found" early exit is
gone. It still does **not** work: network tasks reject the available default SSL
provider, Conscrypt loading fails, and activity startup exits 10 with
`IllegalStateException: Required value was null` from `aqhy.r`/`aqhx.close` in
`MainActivity.onStart`. Read-only inspection places the latter in lifecycle
tracing; the root cause is not established. No tracing/signature/TLS check was
bypassed and no app bytecode was changed to hide it. These need separate fixes.

Private logs: `/home/morten/src/webview-tests/revanced/` and `gms-install.log`.
