# Internal installation must not execute the application

Build inside the existing Alpine development environment:

```
sh tests/internal-install/build.sh /path/to/atl/build /tmp/internal-install-fixture
```

The signed APK has an Application constructor and onCreate that deliberately
throw. In a disposable ANDROID_APP_DATA_DIR with the configured runtime and
verifier, run the launcher with `probe.apk --install-internal`: require exit 0,
an identical `_installed_apks_/org.atl.internalinstall.apk`, and no
`INSTALL_MUST_NOT_` marker. Launching the same APK normally must fail with
`INSTALL_MUST_NOT_CONSTRUCT_APPLICATION`; this confirms the fixture can expose
accidental application startup. No activity is required for installation.

Replace the disposable profile's `_installed_apks_` directory with a regular
file and retry: require a nonzero exit and the directory creation error. Do not
run these failure tests against a real profile.
