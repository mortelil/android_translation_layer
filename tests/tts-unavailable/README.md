# Unavailable text-to-speech engine

Build: sh tests/tts-unavailable/build.sh build-mobile OUTPUT
Run the APK in ATL; expect TTS_UNAVAILABLE_PASS.
Checks both constructors report ERROR asynchronously exactly once on the main
Looper, and a null listener is accepted. This is an ATL no-backend test, not an
Android equivalence test: Android may have an installed TTS engine. No synthesis
success is reported and no speech engine is implemented by this change.
