# LocalSend Android compatibility

LocalSend 1.18.2 (version code 641), x86_64 and arm64-v8a, was tested on
2026-10-08. These are the Android APKs running through ATL, not LocalSend's
native Linux release. The APK and upstream LocalSend sources are unmodified.

## Changes

* `WifiInfo.getIpAddress()` returns zero when no Wi-Fi IPv4 information is
  implemented. Returning -1 incorrectly advertised the broadcast address.
  This does not implement Wi-Fi configuration or make Ethernet a Wi-Fi device.
* The companion Bionic change translates Android's `getnameinfo` flags and
  error codes. Without it, Dart's network-interface enumeration returned DNS
  names instead of numeric addresses. This belongs in Bionic, not in the app.
* `ATLDocumentsProvider` exposes files and directory trees selected through
  the native Open File/Select Folder dialogs as document content URIs.
  Metadata, MIME types, directory queries and read-only file descriptors are
  implemented. Grants can be persisted in app-local preferences and released.
  `Activity` advertises read/persistable flags; `ContentResolver` routes grant
  operations and `DocumentsContract` recognizes the built-in provider.
* Install `font-roboto` in the Alpine runtime. LocalSend otherwise rendered
  icons without text. No font substitution or APK modification was needed.

## Scope of document support

This is a read-only local documents provider, not a complete Android Storage
Access Framework. The native chooser currently returns one selection even if
an app requests multiple files. A selected directory can supply multiple files.
Create, rename, delete, writable tree grants, third-party document-provider
grants and persisted-grant enumeration are not implemented. LocalSend's default
receive directory uses ordinary app-local filesystem access and is independent
of these read-only document grants. Custom SAF receive destinations are not
supported by this change.

Only selected roots are exposed through this provider. Canonical target checks
reject traversal and symlinks escaping a selected tree. These checks are not a
security boundary for native Android code: ATL does not sandbox the process,
and canonical-path checks followed by open are not race-free against concurrent
filesystem changes. Persistence records URI access, not copies of file content;
moving/deleting a file can invalidate its URI. Do not describe this as an Android
permission sandbox.

The implementation and tests were written for this fork with AI assistance;
no AOSP class was copied for the new provider. Existing AOSP file headers are
preserved. The relevant API conventions were checked against Android's
WifiInfo, DocumentsContract and Bionic netdb declarations.

## Validation

`TestDocuments` in the mobile-smoke APK checks selected-file metadata and bytes,
restoring access from persisted grants after clearing transient state, release,
write denial, directory enumeration, child reads, traversal and escaping symlinks.
It runs with synthetic files in test appdata. The test passed on x86_64 and
ARM64. This is not yet a separate-process persistence regression test.

The Bionic native test checks linker lookup of the translated `getnameinfo`,
numeric IPv4/IPv6 addresses and ports, invalid flags and buffer overflow.
Both architectures passed. The full existing mobile test script also passed
on x86_64; the smoke APK passed on ARM64.

Live validation used isolated LocalSend data directories and synthetic text
files. PC reception from a test protocol client and PC-to-phone sending through
LocalSend's native file picker completed with matching SHA-256 hashes.
The two app instances discovered each other automatically.

## Running

Build the three sibling forks as described in `MobileFork.md`, install
`font-roboto`, then invoke `scripts/mobile/run.sh /path/to/LocalSend.apk` inside
the Alpine runtime. Use `ANDROID_APP_DATA_DIR` to select a separate data root.
For X11, `GDK_DISABLE=glx` selects the EGL path used in this test. The tested
render scales were 2 on PC and 2.65 on the phone; these are display-specific.
For a Plasma Mobile session, supply its `XDG_RUNTIME_DIR`, `WAYLAND_DISPLAY`
and `DBUS_SESSION_BUS_ADDRESS`, with `GDK_BACKEND=wayland`.

## Repeated-transfer crash

A native debugger trace showed LocalSend's Flutter background engine calling
host `eglTerminate` when replaced for the next transfer. Mesa then invalidated
the display also used by the foreground engine and GTK. The next rendering
operation failed with EGL_BAD_CONTEXT and GTK subsequently crashed.

`egl_lifecycle.c` now counts Android initialization clients and records GTK's
ownership of the platform display returned by ATL. An Android client dropping
its reference cannot terminate GTK's display. Non-host displays are terminated
when their last tracked client releases them. Native symbol lookup, EGL procedure
lookup and the Java EGL binding use the same implementation. This does not
virtualize every EGL object or compensate for unbalanced client calls; apps
must still destroy their own contexts and surfaces. GTK owns host teardown.

`sh tests/egl-lifecycle/run.sh` uses real surfaceless Mesa contexts to check
multiple clients, repeated background teardown, survival of the host context,
actual termination of a non-host display and invalid-display errors. It passed
on x86_64 and ARM64. Two consecutive live receive sessions that previously
reproduced the native crash completed with HTTP 200 after the fix. The full
x86_64 mobile regression suite also passed with this test included.

After deployment to both devices, a file selected by the user in the phone's
native chooser was sent phone-to-PC successfully (96.2 KB). The source and
received files had identical SHA-256 hashes, and both app UIs displayed Done /
Finished. This verified real URI-backed sending on ARM64 and a third receive
session in the same fixed PC process. No changes to the APK were required.

An earlier zero-size transfer also produced LocalSend Dart progress errors
(`Infinity or NaN toInt`). The native crash was separately reproduced using a
nonempty synthetic file and fixed in ATL. Zero-size progress handling has not
been retested after that fix and is not claimed to be corrected.
