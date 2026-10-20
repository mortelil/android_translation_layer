# Editable append contract

Compile against the Android SDK android.jar (ANDROID_JAR), then run on ATL.
The probe calls all three overloads through an Editable reference, tests return
identity, text contents, span copying, and dispatch through Appendable. This catches
missing covariant interface signatures even when SpannableStringBuilder already
implements the operations. Runtime must print `EDITABLE_APPEND PASS` without errors.

Build in the development container: `sh tests/editable-append/build.sh BUILD OUT`.
