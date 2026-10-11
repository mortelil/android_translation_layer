# ImageView scaling pixels

Inside the Alpine build environment, run from the repository root:

```
xvfb-run -a sh tests/imageview-scale/run.sh build-mobile /path/to/runtime-prefix /tmp/imageview-scale
```

The test links the selected ATL build and calls the production JNI scale setter
without starting an APK or JVM. A 20x10 red texture is rendered inside a 100x100
GTK picture; downloaded pixels verify size and centering for CENTER_INSIDE,
FIT_XY, FIT_CENTER, CENTER_CROP, then CENTER_INSIDE again. This catches unwanted
upscaling and stale mode selection. No personal data or application is used.

CENTER, MATRIX, FIT_START/FIT_END, bitmap density and Android padding are not
covered or implemented by this patch. No claim of full ImageView parity.
