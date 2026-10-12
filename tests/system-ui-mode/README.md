# Concrete system appearance

Inside atl_dev, build with `sh tests/system-ui-mode/build.sh BUILD OUTPUT`.
Run OUTPUT/system-ui-mode.apk through scripts/mobile/run.sh with ATL_BUILD_DIR
and a private ANDROID_APP_DATA_DIR, under dbus-run-session + Xvfb. With no
settings portal, require SYSTEM_UI_MODE_PASS and exit 0. The old implementation
fails with undefined system night mode. The test switches to the opposite mode
and back to the system mode, requiring convergence in one resource update.

For dark fallback, use an isolated XDG_CONFIG_HOME containing
`gtk-4.0/settings.ini` with `[Settings]` and
`gtk-application-prefer-dark-theme=true`; expect mode 33 (normal/dark), versus
17 (normal/light) by default. Android Configuration delta semantics remain
unchanged: NIGHT_UNDEFINED still means "do not update".

Portal no-preference maps to light, consistent with ATL's existing GTK mapping;
missing portal uses GTK's preference. This does not add live configuration
propagation to existing activities or a UiModeManager service.
