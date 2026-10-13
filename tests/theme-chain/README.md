# Asset sharing and theme copying

Run inside Alpine with ATL_PREFIX set to the exact runtime under test:

```
sh tests/theme-chain/run.sh framework-res.apk app.apk package:Style package:attr...
```

The first attribute must be an app attribute. The probe exercises style resolution,
copying across configuration AssetManagers, empty-source clearing and the negative
case of independently loaded app assets. It calls Theme_setTo directly: the OMP
candidate's replay workaround was deliberately removed, since replay can replace
source values with destination night-mode values. The native reference-remapping
repair and its day/night fixture are in ART tests/theme-copy.

The second probe checks cache identity, failed-load retry, atomic APK replacement
and removed paths, using a private temporary copy of the supplied APK. No source
APK is altered. ATL_THEME_TEST_OUT optionally selects an output directory.

Adapted from OMP Sketchbook f81a24b (also shared by VLC). Original test Apache-2.0
attribution is retained. Cache/helper code is GPL-3.0-only. The cache keeps assets
alive for the process lifetime, as existing managers retain raw references;
there is no eviction or recursive directory-content tracking in this patch.
