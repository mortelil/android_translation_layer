# Resource font lookup

Build inside Alpine: `sh tests/resource-font/build.sh build-mobile /tmp/font-probe`.
Run the resulting resource-font.apk with ATL in an isolated app-data directory.
Success prints `RESOURCE_FONT_PASS`. The test uses the existing DejaVuSans fixture
from tests/font-probe/assets (and its existing license); it does not download fonts.

Covers raw TTF and XML font-family resources, caching, absent/@null values,
unresolved theme attributes, integer attributes, invalid fonts, and recycled arrays.
The implementation supports bundled fonts, TTC index, weight, slant and variation
metadata. Downloadable font providers remain explicitly unsupported.

TypedArray.getFont is adapted from AOSP frameworks/base commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d (android-17.0.0_r1),
core/java/android/content/res/TypedArray.java. Resources.java/ResourcesImpl.java
and FontResourcesParser.java at the same commit are contract references. Existing
Apache-2.0 source headers are preserved. ATL uses its existing native FontFamily
loader for the resource bytes.
