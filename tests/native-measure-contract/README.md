# Native measurement contract

Run in `atl_dev`, with `ATL_BUILD_DIR`, `ATL_PREFIX` and `ATL_CORE_JAR`
selecting the candidate:

```
sh tests/native-measure-contract/build.sh "$ATL_BUILD_DIR" /tmp/measure-apk
sh tests/native-measure-contract/run.sh /tmp/measure-apk/probe.apk /tmp/measure-run
```

The probe uses public Android APIs. It verifies that a wrapping TextView's
natural width does not change when its exact height changes, that AT_MOST
caps that width, and that narrower exact widths do not reduce wrapped height.
This covers a contributing measurement error found while investigating OsmAnd,
without network requests or map downloads. It does not by itself prove that
OsmAnd's final GTK allocation is correct. The attached-view case also verifies
that changing a nested button label updates its measured and laid-out width. Before the fix, "Download 226.10 MB" measured 171px
unconstrained but 86px with an exact height of 42px on the test host.

Reference: AOSP android-16.0.0_r1, core/java/android/widget/TextView.java,
onMeasure: desired text width is resolved against widthMeasureSpec before
heightMeasureSpec limits the result. GTK height-for-width widgets likewise
need their unconstrained natural width before measuring height. No AOSP code
is copied by this patch. Pixel values are font-dependent; the test compares
measurements of the same text rather than hardcoding a particular font width.

This fixture does not validate full TextView typography or all GTK widget
request modes. Exit status and the PASS marker must both succeed.
