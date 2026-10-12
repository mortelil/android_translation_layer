# Native Choreographer frame callbacks

This implements `AChoreographer_getInstance` and the API 24/29 frame callback
functions (legacy `long` and 64-bit timestamps, immediate and delayed). It
replaces the rejected OMP global-idle proposal with a per-Looper scheduler.
There are no package-name exceptions or signing-identity changes.

## Contract and integration

- An instance belongs to the calling thread's prepared ALooper. Without a
  Looper, getInstance returns NULL. Other threads can post to that instance;
  callbacks only run when its owner polls the Looper.
- A timerfd registered with the existing Looper/GLib bridge carries ready work.
  The GTK window's frame clock supplies CLOCK_MONOTONIC nanosecond timestamps.
  All callbacks in one batch receive the same timestamp. Delayed callbacks do
  not execute before their deadline; reentrant posts wait for a later frame.
- The queue is locked during posting, frame selection and teardown. Callbacks
  run outside the lock. A stalled owner retains at most one ready batch; later
  requests remain pending. Equal-deadline callbacks preserve submission order.
- GTK frame requests stop when there is no due work. Far-future requests do not
  keep a tick callback running. Without a mapped window, a 60 Hz timer fallback
  supplies frames. A 250 ms watchdog also permits progress if a mapped window's
  compositor stops ticking. These fallback timestamps are **not hardware vsync**.
- Thread exit removes the descriptor and queued callbacks and releases the
  Looper reference. As on Android, callers must not retain/use an instance
  after its owner exits. The window is weakly held and bound on the GTK thread.

The existing C bridge leaked temporary C++ `sp<Looper>` references from
`getForThread()` and `prepare()`. It now releases those temporary references
while preserving the Looper's thread-local ownership. The thread-exit descriptor
test fails against the old bridge and passes with this fix.

This is not a full SurfaceFlinger implementation. API 30 refresh-rate listeners,
API 33 frame timelines, presentation fences, and Android's complete input/sensor
phase ordering are outside this patch. Their symbols are not fabricated.
The existing Java Choreographer implementation is unchanged. Only the launcher's
main window supplies native frame timing; independent per-display clocks are
not implemented. Physical refresh accuracy on Nura/Wayland and 32-bit ABI
execution have not been tested in this change.

API reference: [Android NDK Choreographer](https://developer.android.com/ndk/reference/group/choreographer).
Signatures also checked against the installed AOSP `android/choreographer.h`;
the delayed64 argument is `uint32_t`, not `long`. Looper ownership was checked
against `system/core/libutils/Looper.cpp` in the ART dependency. This is a new
Linux/GDK implementation, not a copied AOSP implementation.

## Reproducing tests

Inside the Alpine development container, from the repository root:

```sh
sh tests/choreographer/run.sh /tmp/atl-choreographer-tests
```

Requires a C compiler, GTK4 development files, Xvfb, dbus-run-session, and the
same libutils/libcutils/liblog runtime used by ATL. Set `ATL_PREFIX` when it is
not `$HOME/ATL/.atl-runtime`. The runner compiles the actual production C sources,
not a mock scheduler, and runs headless and mapped-window cases separately.

Assertions cover Looper ownership, cross-thread posting and 400 concurrent
requests, once-only delivery, shared batch timestamps, delay/overflow handling,
callback reposting, legacy ABI, idle blocking, and thread-exit descriptor cleanup.
Eight consecutive mapped-window callbacks must match the observed GDK frame
timestamp and increase monotonically. Destroying the window must leave fallback
delivery working. Both cases passed on x86_64 on 2026-10-12.

The complete ATL build and mobile-smoke suite also passed. App tests used this
checkout's build-mobile, private Xvfb displays and dummy profiles, compared with
the preceding `f0078822` integration results:

| App | Test | Result |
| --- | --- | --- |
| Immich 3.3.0 | Render server screen, enter dummy endpoint, close | Responsive; normal exit |
| LocalSend 1.18.2 | Receive, Send and Settings navigation, close | Responsive; normal exit |
| Messenger 558 | Enter dummy email and masked password, close | Responsive; normal exit; existing outline artifacts remain |
| Stock YouTube 21.41.525 | Startup and close | Native Choreographer link failure gone; now displays unsupported Google Play services dialog. Not functional. Closing exposes missing PackageManager.isPackageSuspended and exits 10. Logs also report missing thermal-status and AppOps methods. |

No real login, file transfer, media playback or Nura test was performed for this
patch. Signal was not started. microG/ReVanced were not rerun; their preceding
results are not counted as fresh passes. Private logs, screenshots and build
hashes: `/home/morten/src/choreographer-tests/` (not committed).
