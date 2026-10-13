# Local media browse and surface contracts

Build inside the Alpine ATL container:

```
R8_JAR=/path/to/r8.jar sh tests/media-browser/build.sh BUILD_DIR OUTPUT_DIR
```

Sign the generated APK with a development key. Run `org.atl.mediabrowse.Probe`
on both Android and ATL; require `MEDIA_BROWSER PASS` and no FAIL. Run
`org.atl.mediabrowse.SurfaceProbe` separately; require `SURFACE_LIFECYCLE PASS`.
The surface test advances on actual creation/change/destruction events, not
assumed startup speed. It checks validity, isCreating, duplicate registration,
removed listeners, dimensions, detach and reattach.

The media test covers a real local service bind, delayed session token, rejected
root, subscription after connection, paging, notification reload, unsubscribe,
null children versus null item results, detached worker completion, main-thread
callback delivery, stale results after disconnect and duplicate-result rejection.
It also checks metadata/icon/URI/extras round trips, local Binder identity and
liveness, SimpleCallback availability, and runtime audio constants by reflection.

Optional launcher case: use `-l org.atl.mediabrowse.Probe -a org.atl.TEST
-t video/mp4 --uri file:///fixture/video.mp4` and require `TYPED_LAUNCH PASS`.
Explicit action/type currently require an explicit activity; implicit MIME
intent resolution is not part of this change.

Optional overflow case: pass string extra `overflow=1` (`-e overflow=1` in ATL,
`--es overflow 1` with Android am). ATL must return an empty page for the huge
positive page/page-size values. The tested Android API 37 emulator crashes with
`fromIndex(1) > toIndex(-2147483648)` in its paging implementation. This is an
intentional robustness improvement, not a passing Android comparison. Keep this
case separate so it does not obscure the shared contracts.

## Source and scope

MediaBrowser, MediaBrowserService, MediaBrowserUtils and local Binder behavior
are adapted from AOSP frameworks/base android-8.1.0_r1, commit
250714c67ba9d2029bc88284672949a87b243b9a, under Apache-2.0.
Audio constants/AudioTimestamp are from android-15.0.0_r1, commit
4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3. Source license notices are retained.
The OMP VLC lane (a74d777) supplied the initial browse/metadata, SimpleCallback,
launcher and audio API candidates. Review replaced the browse state machine,
repaired omitted parcel fields and corrected erroneous audio/channel constants.
The candidate's silent AudioTrack sink and ignored nonblocking write mode are
not included.

The browse transport is deliberately local: no remote Binder proxy or
cross-process media protocol is claimed. Unsupported remote binders fail the
connection, and service identity is restricted to the hosting package/process.
Session transport/control completeness, decoding, routing devices and audible
playback are not established by these tests. Styled metadata parceling remains
unsupported and throws rather than silently dropping spans. Bitmap parceling
supports ATL's existing texture-backed pixel formats; unsupported formats throw.
The Parcel format is ATL-internal, not Android native wire-format compatibility.

The SurfaceView change corrects callback registration, validity and widget
realize/unrealize lifetime. Fixed buffer size/format, z-order and Android
compositor integration are outside this change. A black VLC window is not
accepted as video presentation evidence.
