# App-level smoke evidence (lane nsd)

Build under test: lane-nsd `build-mobile` (commit a930ea69 + the JNI signature
fix 8280a24b), frozen env `~/ATL/compat-fixes/env` (bionic 22eb732a,
art 327b9c2e, prefix `.atl-runtime`).
Fixtures live in the evidence dir `/home/morten/ATL/agent-work/COMPAT-FIXES/nsd/`.

## Home Assistant onboarding — PASS (`ha-onboarding2/`)
    ATL_UI_GEOMETRY=421x924 ATL_UI_ATL_ARGS='-w 421 -h 924' \
    ATL_UI_SCRIPT='wait:20;shot:welcome;click:119,803;wait:15;shot:discovery' \
    bin/run.sh lane-nsd lane-nsd/build-mobile apks/homeassistant.apk \
      nsd/ha-onboarding2 ATL_UI_SMOKE_DONE 250 ui

With the synthetic `_home-assistant._tcp` service live, the welcome screen
renders and the click leaves it; the discovery screen then loads and lists the
synthetic instance (tesseract on shot-discovery.png):

    Home Assistant / Searching on home network
    ATL-Synthetic-HA   http://127.0.0.1:8123
    ATL-Synthetic-HA   http://127.0.0.1:8123
    ...
    Enter address manually

App log (atl-ui-stderr.log:149-157), repeated because Home Assistant runs
discovery in several flows:

    I/HomeAssistantSearcherKt: Service discovery found HA: name: ATL-Synthetic-HA type: _home-assistant._tcp host: null port: 0
    I/HomeAssistantSearcherKt: Service discovery found HA: name: Hjem type: _home-assistant._tcp host: null port: 0
    I/HomeAssistantSearcherKt: Service discovery found HA: name: Home type: _home-assistant._tcp host: null port: 0

The app uses only the 3-argument `discoverServices(String,int,DiscoveryListener)`,
`stopServiceDiscovery(listener)` and `resolveService(NsdServiceInfo,ResolveListener)`
(dexdump of classes7.dex) — no API-34 executor overload is needed.
No `NoClassDefFoundError`/`NoSuchMethodError`/abort; no real server login or
contact with the real `Hjem`/`Home` instances.

## KDE Connect — NOT TESTED (blocked earlier, unrelated) (`kdeconnect/`)
Launch smoke only. The app exits before reaching mDNS: Compose/AppCompat
`Toolbar` init throws
`UnsupportedOperationException: Failed to resolve attribute at index 4: TypedValue{t=0x2/d=0x7f040298}`,
then `System.exit status: 10`. No `NsdManager`/`servicediscovery` line is ever
reached, so no nsd requirement can be judged from this run.

## Fixture withdrawal
    toolbox run --container atl_dev sh -lc '<nsd_fixture ... &>; ... kill -TERM; wait'
    live_matches=1
    fixture_exit=0
    FIXTURE_STOPPED _home-assistant._tcp ATL-Synthetic-HA
Afterwards no `nsd_fixture` process remains and `nsd_browse _home-assistant._tcp`
reports 0 matches for `ATL-Synthetic-HA` (other LAN instances untouched).
