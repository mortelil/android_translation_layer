# Rhythm library-scan probe — observed results

App: `chromahub.rhythm.app` (rhythm.apk), ATL checkout `compat/media`
(@f6da2683), frozen baseline 76062b4c.

Reproduce:

    tests/media-scan/run-app-scan.sh <evidence-dir> ['<ui script>']

which generates the synthetic root (`media/`, 4 containers with four distinct
constructed property sets, names carry no metadata), a fresh app data dir
(`data/`) and runs the app under `ATL_MEDIA_ROOT=<evidence>/media` with
`ATL_DEBUG_MEDIA=1`.

## What the app does

1. `ATLMediaContentProvider`/`ATLMediaIndex` indexes the synthetic root; the
   app queries it through `query(..., Bundle args, ...)`:
   `ATL MediaStore: returned 4 rows` (app-scan1/out/atl-ui-stderr.log:486).
2. The app's own library optimisation runs and drives
   `android.media.MediaExtractor` for every file — the code path that used to
   die with `NoClassDefFoundError: android.media.MediaExtractor`
   (AudioFormatDetector). Now it returns real container-header values
   (app-scan1/out/atl-ui-stdout.log:42-78):

       MediaExtractor.getTrackFormat(0) = {sample-rate=96000, durationUs=1250000, mime=audio/flac,  channel-count=2, bitrate=259065, bits-per-sample=16}
       MediaExtractor.getTrackFormat(0) = {sample-rate=48000, durationUs=1750000, mime=audio/mpeg,  channel-count=1, bitrate=64000}
       MediaExtractor.getTrackFormat(0) = {sample-rate=22050, durationUs=3500000, mime=audio/raw,   channel-count=1, bitrate=176400, bits-per-sample=8}
       MediaExtractor.getTrackFormat(0) = {sample-rate=44100, durationUs=2500000, mime=audio/raw,   channel-count=2, bitrate=1411200, bits-per-sample=16}

3. The scan completes in the app's own words:

       notify(null, 1504, Notification [Library Optimization, Library is ready: 4 tracks, 1 albums, 1 artists, []]) called
       (app-scan1/out/atl-ui-stdout.log:93)

4. The app persists its own extraction in `databases/rhythm_database`
   (`songs` table) — every value matches the fixture manifest and is
   distinguishable per container:

       id  title             duration  sampleRate  channels  codec  bitrate
       1   atl-media-04.flac     1250       96000         2  FLAC    259000
       2   atl-media-03.mp3      1750       48000         1  MP3      64000
       3   atl-media-02.wav      3500       22050         1  PCM     176000
       4   atl-media-01.wav      2500       44100         2  PCM    1411000

   `duration` is ms; `bitrate` is a rounded bit/s value derived by the app.

## Blocker found: onboarding wizard

The app opens on a "Welcome to Rhythm" screen and, after `Get Started`, stops
on "Choose Your Playback Mode" (five screenshots over ~100 s identical). Clicks
on the two choice cards and the bottom area, and `Tab`/`Return` keyboard
stepping, do not advance the wizard under ATL, so the library *screen* was not
reached in this probe. This is a UI-interaction blocker, not a media-API
blocker: the metadata pass above runs and finishes before/independent of it
(the scan notification is already emitted while the wizard is on screen).

NOT TESTED: library screen rendering with the 4 scanned tracks; track
properties as displayed in the UI.
