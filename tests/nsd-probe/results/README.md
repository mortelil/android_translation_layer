# NsdManager probe results

Signed probe APK: `../build/nsd-probe.apk` (shared test alias `fontprobe`,
`~/ATL/font-tests/debug.keystore`), run in `probe` mode with the synthetic
`_home-assistant._tcp` service advertised by `tests/nsd/nsd_fixture`.

## run2-discovery-resolve.json — PASS
Backend reachable (host avahi daemon shared into atl_dev):
- discover: `started=true`, `startFailed=false`, found the synthetic
  `ATL-Synthetic-HA` plus real LAN instances (`Hjem`, `Home`; names only).
- resolve: `ok=true`, name `ATL-Synthetic-HA`, host `172.17.0.1`, port `8123`,
  all seven TXT keys from the fixture with their real values.
- generations: stale handle (stopped discovery) dropped (`staleDelivered=false`),
  live handle delivered (`liveDelivered=true`).
- listenerReuse: second `discoverServices()` with an active listener throws
  `IllegalArgumentException: listener already in use`; stopping an unregistered
  listener also throws.
- maxLimit: 3 extra discoveries start, the 5th reports
  `FAILURE_MAX_LIMIT` (4) and never starts.
- cleanup: `finalActive=0`.

## run3-backend-unavailable-results.json / .log — start FAILS (expected)
Run with `DBUS_SYSTEM_BUS_ADDRESS=unix:path=/nonexistent`:
`W/NsdManager: avahi_client_new failed: Daemon not running`, so the discovery
start fails; the probe then throws `IllegalArgumentException: listener not
registered` when it stops that listener, which confirms the listener was never
registered (i.e. the start did not silently succeed). The probe should guard
that stop for the backend-unavailable case.
