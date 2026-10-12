# Typed Parcel presence markers

Inside atl_dev: `sh tests/parcel-typed/run.sh BUILD OUTPUT`.
This compiles the actual Parcel.java against ART declarations and runs its
pure Java typed-container operations on the container JVM. Expect
PARCEL_TYPED_PASS. PARCEL_SOURCE can select a preserved baseline Parcel.java;
the pre-fix source fails "Typed values lost".

Tests four-byte non-one markers as used by SafeParcel, null entries, arrays,
preallocated arrays, lists, writer layout, null/empty containers and the next
field's position. This is not only a writer/reader round trip: manually encoded
integer markers test the Android contract independently.

Reference: AOSP Parcel.writeTypedObject/readTypedObject and typed container
methods, android-17.0.0_r1, 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d.
This patch does not make ATL's DataStream-based Parcel binary-compatible with
Android Binder/native Parcel or change general boolean encoding. Persisted
ATL-specific typed-container blobs from the old broken format are not migrated.
