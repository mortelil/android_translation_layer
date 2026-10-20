# Streaming AudioTrack and VLC prerequisites

This milestone supports mono/stereo PCM16 streaming: frame-aligned transfer
counts (in the caller's units), direct/heap ByteBuffers, blocking/nonblocking
writes, playback-head advancement/reset/wrap, interruption, and safe release
with active writers. The native handle owns a reference and each JNI call owns
another; detaching a released handle prevents new acquisitions and the final
caller frees the state. Stop drains with nonblocking ALSA calls so release can
interrupt it. Stopped writes cannot trigger ALSA automatic playback.

Static tracks, float/compressed PCM, offload, routing callbacks and position
notifications are not implemented by this milestone. Unsupported track modes
and formats remain uninitialized rather than claiming successful playback.
This is not a complete Android audio service or full device-routing model.

The probe also checks theme attribute resolution, copying applied theme styles
across AssetManagers, and retention of RotateAnimation's pivot matrix. The
animation scheduler/XML inflater remains outside scope. AOSP sources and
provenance are recorded beside the adapted implementation; the public tests
run unchanged on Android, except for ATL's private playback-head wrap seam.

Build inside Alpine:

```sh
sh tests/audio-probe/build.sh /absolute/build-dir /absolute/probe-output
```

For the host-side suite, set `ATL_PROBE_RUNNER` to the external
`run-atl-probe-lane.sh` harness, and set `ATL_PROBE_BUILD_DIR`,
`ATL_PROBE_PREFIX`, and `ATL_PROBE_CORE_JAR` to the isolated build and companion
runtime. Then run `sh tests/audio-probe/run.sh /absolute/results sink` (or
`nosink` for the negative control). This creates a temporary PulseAudio null
sink, records only its monitor, and removes it on exit. A successful return
requires both contract assertions and actual expected signal content; missing
hardware is not treated as a passing positive audio test.

To compare on Android, align/sign the generated APK with a debug key, install
only `org.atl.tests.audio`, and start `.AudioProbeActivity`. Require the final
`AUDIO_PROBE_DONE failures=0 sink=yes` marker. A missing native wrap seam is an
explicit Android-only skip, not a pass.

Integration evidence (2026-10-14): the contract probe passes ATL and API 37;
a separate sink capture contains the 1 kHz tone written from an odd byte offset.
The missing-device control produces errors and no tone. Mobile-smoke passes.
VLC's synthetic video advances between captured frames and its 440 Hz audio is
present at the isolated sink. A click did not expose playback controls in that
run; complete VLC UI compatibility and hardware-device routing are not claimed.

The nonblocking paced-playback row pumps up to 20 ms of PCM every 10 ms for
two seconds and requires at least 85% real-time playback-head progress. This
regresses the one-ALSA-period-per-call cap that slowed ReVanced playback to
about two thirds of normal speed. Short writes on a full device remain valid;
the existing full-queue tests cover that case.
