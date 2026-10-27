# Firefox browser integration fixture

Serve this directory only on localhost (`python3 -m http.server 18871 --bind
127.0.0.1 --directory tests/firefox-browsing`) inside the same container as ATL.
Use a separate ANDROID_APP_DATA_DIR. Open http://127.0.0.1:18871/ in Firefox.

Verify: text entry, Check input changes both text and color immediately; second
page link; browser back; wheel-scroll to SCROLL TARGET; resize and scroll back.
These must update without requiring a navigation to refresh the displayed frame.
Also verify a public HTTPS page. Do not sign in or use private browsing data.

Validated on Alpine x86_64 with Firefox 157.0.1 (2016188623), ATL's remote
Surface bridge and bionic_translation 17aa0b6. The Bionic readdir fix is needed:
Firefox's nested directory enumerator otherwise exhausts its stack. Nura/ARM,
media playback, downloads, WebRTC and browser extensions are not covered.
