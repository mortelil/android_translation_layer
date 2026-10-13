# Component lookup contracts

This repairs OMP OsmAnd ec095169's manifest-preservation finding. Unlike the
candidate, lookups do not add CONFIG_UI_MODE. Package, class and requested flags
are used for activity, receiver, service and provider lookup. Missing or excluded
components throw NameNotFoundException. Returned component records, metadata
bundles and ApplicationInfo records are detached from parser-owned objects.

Contract reference: AOSP frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/content/pm/PackageManager.java. No new AOSP code copied.
Existing PackageParser generation/filtering is reused; this is not a new
package installation backend or complete deep-copy implementation of all nested
ApplicationInfo/ProviderInfo arrays. API33 long-flag overloads are separate work.

The same APK passes 41 checks on ATL and Android API37:
- correct component type/name, metadata requested/omitted;
- manifest configChanges preserved, including after caller mutation;
- changed result fields/metadata do not affect subsequent lookups;
- disabled provider requires GET_DISABLED_COMPONENTS;
- absent classes and wrong packages throw for all four component types.

Android37 adds compatibility config bits (observed 0x44fb versus ATL 0x480).
The test compares against each platform's package record and separately asserts
the declared orientation/screenSize bits and absence of undeclared UI_MODE.
Old ATL fails check 3 (metadata request ignored); this is a negative control,
not merely a build test.

Build Java/dex inside Toolbox:
sh tests/component-lookups/build.sh BUILD OUTPUT
Package AndroidManifest.xml and classes.dex with Mint Android SDK aapt and sign
with a test key. Launch org.atl.componentlookups/.Probe on both runtimes.
Require COMPONENT_LOOKUPS PASS checks=41 and fresh files/results.json on ATL.
No network or personal app data is needed.
