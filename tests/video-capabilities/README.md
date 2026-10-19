# Video capability queries

Build in Toolbox: `sh tests/video-capabilities/build.sh build-mobile OUTPUT`.
Sign the APK and run `org.atl.videocapabilities/.Probe` on Android and ATL.
Expected: VIDEO_CAPS_PASS (counts depend on enumerated decoders).
The same probe checks range consistency, invalid dimensions, conditional ranges,
and rejection of infinite rates. Android's device-specific ranges need not equal
ATL's software policy.

ATL advertises a conservative software envelope: axes up to 4096, total pixels
up to 4096*2160, nominal rates 0..60. Conditional width/height queries retain the
pixel budget, so 4096x4096 is not supported while portrait 2160x4096 is. This is
an explicit advertisement policy, not FFmpeg maximum discovery or a benchmark.
It replaces the old predicate accepting every positive size and frame rate.
The decoder may accept streams outside this advertised subset. Profile, level,
bit-depth and hardware acceleration constraints are not fully modelled yet;
existing CodecProfileLevel advertisements are not expanded by this patch.
getAchievableFrameRatesFor still returns null for supported dimensions because
no device-specific throughput measurements are available. Playback is separate
integration work, not implied by a capability probe passing.

Contract reference (no copied implementation): AOSP frameworks/base
android-14.0.0_r1, media/java/android/media/MediaCodecInfo.java,
VideoCapabilities range getters and size/rate query methods.

Additional tests:
- In Toolbox: `sh tests/video-capabilities/policy.sh OUTPUT/policy` checks the
  explicit ATL envelope (12 assertions including pixel budget and NaN).
- Generate tiny single-frame samples using `sh tests/video-capabilities/fixtures.sh OUTPUT`
  on a host with FFmpeg encoders. In Toolbox compile `decode-frame.c` with
  `cc tests/video-capabilities/decode-frame.c -o OUTPUT/decode-frame $(pkg-config --cflags --libs libavcodec libavutil)`.
  Run `OUTPUT/decode-frame h264 OUTPUT/frame.h264` and similarly vp8/vp9.
  Each must report DECODE_FRAME_PASS at 4096x2160. This validates the linked
  decoder library at the advertised picture-size edge, not ATL's packet queue,
  GPU presentation, all profiles or sustained 60 fps playback.
