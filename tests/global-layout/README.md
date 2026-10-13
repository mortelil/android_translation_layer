# Global-layout registration lifecycle

OMP OsmAnd d6f82b27 identified callbacks lost when registered before mapping.
The native repair keeps one window-owned registration, connects after mapping,
disconnects while unmapped, and releases clock/JNI references on removal or
window destruction. Java's AOSP CopyOnWriteArray dispatch is unchanged.

The same probe on Android and ATL registers before attachment/first frame,
checks nonzero allocated size, cancels listeners before dispatch, removes the
last listener from its callback, reconnects on the next UI turn, and checks
exactly one delivery per listener. A second stage registers/removes a listener
on an unmapped dialog and verifies its first layout after show(). The baseline
exits in the unmapped-dialog cancellation path; both ATL with the repair and
Android API 37 pass. Require GLOBAL_LAYOUT PASS and fresh
files/results.json. Build with build.sh, package/sign with Mint Android SDK.

This patch covers OnGlobalLayout only. The existing OnPreDraw paint-phase hook
does not implement Android draw cancellation and is not claimed fixed here.
It does not change existing Java-exception policy in native frame callbacks.
