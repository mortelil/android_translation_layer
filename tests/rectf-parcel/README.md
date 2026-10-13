# RectF parcel contracts

OMP OsmAnd commit 01b46343 adds the missing Parcelable contract. Implementation
matches AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
graphics/java/android/graphics/RectF.java; original Apache-2.0 header retained.

This probe checks coordinate order, trailing parcel data, mutation through
readFromParcel, inverted bounds (no normalization), signed zero, NaN/infinities,
creator arrays, typed arrays including null, and Bundle parcel round-trip.
Run the same APK on Android and ATL; require RECTF_PARCEL PASS checks=41 and a
fresh files/results.json. No app data or network needed.

Build Java/dex in Alpine with `sh tests/rectf-parcel/build.sh BUILD OUTPUT`.
Package the manifest and classes.dex on Mint with Android SDK aapt, then sign
using a test key. The launcher activity is org.atl.rectfparcel.Probe.

Parcel typed-array signatures also use Creator<T>/T[] so callers can compile
against ATL as well as the Android SDK; erased method descriptors are unchanged.
