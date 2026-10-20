# MediaPlayer local/HTTP playback contract

Build with `sh tests/media-player/build.sh BUILD OUTPUT` inside Alpine, then
run OUTPUT/media-player.apk through the same build's scripts/mobile/run.sh.
Set ATL_PLAYER_TEST_URI to a 4-second 160x90 MP4 fixture, for example:

```
ffmpeg -f lavfi -i testsrc2=size=160x90:rate=20 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 4 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart sample.mp4
```

Run once with an absolute filename. For the HTTP case start
`python3 tests/media-player/serve.py sample.mp4` and set ATL_PLAYER_TEST_URI to
`http://127.0.0.1:18963/sample.mp4`. The loopback-only server rejects requests
without the required header and supports byte ranges. The player is muted.

The APK exits 0 only after real preparation, duration/dimensions, Looper delivery,
position advance, paused clock, seek completion, EOS, reset, error handling,
release and suppression of stale preparation callbacks pass. A watchdog exits 1.
The missing file must report onError, not onPrepared or a successful completion.
The server log verifies the HTTP and Range requests. No personal media is used.

Also inspect the SurfaceView visually: changing test-pattern frames should be
visible. The callback/clock assertions alone do not prove final presentation.

## Implementation scope

The new GStreamer playbin backend replaces the previous GtkMediaStream skeleton.
It supports filesystem paths, HTTP(S), headers, widget-backed SurfaceView output,
async and sync preparation, pause/start/stop, seeking, looping, volume and basic
callbacks. GTK frame presentation is on its main context; Java callbacks use the
creator's Looper (or main Looper). Reset/release invalidate queued Java events;
native queued frames retain their context until consumed and check closure.

Not implemented: descriptor ranges/content URIs, TextureView/SurfaceTexture
output, DRM, audio-session effects/routing, wake locks, independent left/right
volume (the backend uses their mean), MediaPlayer.create(resource), rich track
selection and timed text. Unsupported sources/surfaces fail explicitly.
This does not bypass HTTP 403 from a server, nor guarantee ReVanced can play every
video. Codec/plugin availability remains platform-dependent.

API/state reference: AOSP frameworks/base, android-10.0.0_r1,
media/java/android/media/MediaPlayer.java. This backend is newly written, not
copied from mediaserver. GStreamer reference:
https://gstreamer.freedesktop.org/documentation/playback/playbin.html
