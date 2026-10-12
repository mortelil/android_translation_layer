# Theme configuration overrides

Inside atl_dev: `sh tests/theme-override/build.sh BUILD OUTPUT`.
Run OUTPUT/theme-override.apk with scripts/mobile/run.sh, ATL_BUILD_DIR and
private ANDROID_APP_DATA_DIR. Require THEME_OVERRIDE_PASS and exit 0.
Uses aapt2 because this container's aapt crashes compiling qualified resources.

Checks defensive copying; rejection of duplicate/late overrides; actual
values-night selection; independent parent/sibling lookups; matching assets
and resources. Baseline fails with NoSuchMethodError; candidate passes.

Methods adapted from AOSP ContextThemeWrapper.java, android-17.0.0_r1,
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d (Apache-2.0). ATL supplies independent
native asset configuration with the same ordered paths. General resource
lifecycle, locale lists and live configuration propagation remain separate.
