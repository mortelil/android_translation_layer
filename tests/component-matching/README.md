# Activity and service matching

Inside atl_dev, with ATL_BUILD_DIR, ATL_PREFIX and ATL_CORE_JAR identifying the
candidate runtime:

    dbus-run-session -- xvfb-run -a sh tests/component-matching/run.sh /tmp/component-probe

The runner builds and installs synthetic caller/owner APKs under a private root.
Sign the same APKs with a test key to compare with Android:

    adb install -r owner-signed.apk
    adb install -r probe-signed.apk
    adb shell am start -n org.atl.componentprobe/org.atl.components.Probe
    adb logcat -d -s ComponentProbe

Require a fresh COMPONENT_MATCH_PASS checks=51 and no COMPONENT_MATCH_FAIL.
The activity finishes before exiting to avoid Android restarting the probe.

Checks cover activity/service result lists, priority, default-only,
GET_RESOLVED_FILTER, disabled components, explicit components, data/type/category
rejection, unknown packages, selectors, empty unrestricted vs package-scoped
queries, and another visible installed package. Negative activity priorities are
intentional: Android clamps positive priorities for ordinary installed apps.
The probe also verifies Intent.resolveActivity, actual service startup rejection
on wrong data/disabled state, one successful service callback, and actual local
activity startup rather than desktop URI fallback.

References: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
ApplicationPackageManager, ResolveIntentHelper, ComponentResolver and
Intent.resolveActivity. This replaces the OMP 9209229d action-only substitutions
with one shared matcher for PM queries and local dispatch, and subsumes the null
guards proposed in e2c1f2f5.

Scope: existing ATL single-user package-visibility model and local activity
dispatch. No new cross-app activity launching, Android chooser/resolver UI,
Direct Boot, instant-app or domain-verification policy. Manifest filter support
is limited to the patterns documented in tests/intent-filter. Existing service
process placement and permissions are not expanded. Unscoped web intents retain
the desktop-browser fallback when no local query result matches. Broadcast
delivery is not changed by this patch.
Android's system-app-specific positive activity priority clamping is not added;
ATL continues to use declared priorities. These tests cover nonpositive activity
priorities and do not claim parity with that installation policy.
