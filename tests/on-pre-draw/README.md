# OnPreDraw draw-cycle (cancel and redraw)

## Contract (pinned AOSP source)

AOSP `android-17.0.0_r1`, commit `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`:

`frameworks/base/core/java/android/view/ViewRootImpl.java`, `performTraversals()`:

```
boolean cancelDueToPreDrawListener = mAttachInfo.mTreeObserver.dispatchOnPreDraw();
boolean cancelAndRedraw = cancelDueToPreDrawListener
         || (cancelDraw && mDrewOnceForSync);

if (!cancelAndRedraw) {
    ...
} else if (cancelAndRedraw) {
    ...
    mLastPerformTraversalsSkipDrawReason = cancelDueToPreDrawListener
        ? "predraw_" + mAttachInfo.mTreeObserver.getLastDispatchOnPreDrawCanceledReason()
        : "cancel_" + cancelReason;
    // Try again
    scheduleTraversals();
} else {
    ...
    if (!performDraw(mActiveSurfaceSyncGroup)) {
```

so a pre-draw listener returning false **cancels the frame** (nothing is drawn)
and a traversal is rescheduled ("Try again"); only otherwise does the frame get
drawn.

`frameworks/base/core/java/android/view/ViewTreeObserver.java`, `dispatchOnPreDraw()`:

```
    public final boolean dispatchOnPreDraw() {
        mLastDispatchOnPreDrawCanceledReason = null;
        boolean cancelDraw = false;
        final CopyOnWriteArray<OnPreDrawListener> listeners = mOnPreDrawListeners;
        if (listeners != null && listeners.size() > 0) {
            CopyOnWriteArray.Access<OnPreDrawListener> access = listeners.start();
            try {
                int count = access.size();
                for (int i = 0; i < count; i++) {
                    final OnPreDrawListener preDrawListener = access.get(i);
                    final boolean listenerCanceledDraw = !(preDrawListener.onPreDraw());
                    cancelDraw |= listenerCanceledDraw;
                    ...
            } finally {
                listeners.end();
            }
        }
        return cancelDraw;
    }
```

The listener set is iterated over a snapshot (`CopyOnWriteArray`), so
add/remove (including a listener unregistering itself) during dispatch is safe:
a listener removed mid-pass is still invoked in that pass, one added mid-pass
starts from the next pass. Returning false from any listener cancels the draw.
`ViewTreeObserver.dispatchOnPreDraw()` and ATL's `CopyOnWriteArray` in
`src/api-impl/android/view/ViewTreeObserver.java` already match this and were
left unchanged.

## This lane's change (VLC follow-up, milestone C)

Before: `Java_android_view_ViewTreeObserver_native_1set_have_1predraw_1listeners`
connected `GdkFrameClock::paint`, which runs **after** the widget tree has been
snapshotted, discarded the `dispatchOnPreDraw()` jboolean (a cancelled draw was
still presented) and leaked the `_REF(this)` JNI reference; `addOnPreDrawListener`
also called `listener.onPreDraw()` immediately ("FIXME haxx"), which is not an
AOSP call site.

Now (same file plus `android_view_Window.c`):

* The observer is stored as a `NewGlobalRef` under the GtkWindow data key
  `atl-predraw-observer` (`g_object_set_data_full`, destroy-notify =
  `DeleteGlobalRef`), so window teardown releases the JNI ref. `have_listeners`
  false clears it; true installs it once and queues one frame. This mirrors the
  `atl-global-layout-registration` (#48) ownership pattern, which is untouched.
* `atl_view_tree_observer_dispatch_pre_draw(GtkWidget *window)` is called at the
  **top of `atl_scale_root_snapshot()`** — ATL's real pre-draw boundary, since
  the whole window content is presented through one `ATLScaleRoot`
  (GtkStack -> activity decor). It takes a `NewLocalRef` before calling
  `dispatchOnPreDraw()` so a listener that drops the last registration mid-call
  cannot free the object being called, and returns FALSE when the result is
  TRUE (cancel). On cancel the caller returns **without**
  `gtk_widget_snapshot_child()`, so nothing of the hierarchy is presented in
  that frame, and the next frame is queued through
  `g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, ...)` -> `gtk_widget_queue_draw`
  (same deferred idiom as `wrapper_widget_queue_draw`; never re-entrant).
* The immediate `listener.onPreDraw()` in `addOnPreDrawListener` and the
  now-unused `onPreDraw_signal_handle` field were removed.

Per-window dispatch matches AOSP: each Window (activity or dialog) has its own
observer, keyed here by its own GtkWindow.

## Probe

`Probe.java` fills the window with opaque magenta (`#FF00FF`) and cancels every
frame from an `OnPreDrawListener` until a flag flips at 3000 ms, so the frames
before that would have painted the colour but must not be presented; the
`allowed` shot must contain it. Later stages (all on AOSP snapshot semantics)
cover: self-unregistration during dispatch while a later listener is still
reached, adding a listener and removing another during dispatch (removed one
still runs in that pass, added one only in the next), dropping the last
registration and installing again, and leaving a listener registered for window
teardown. Results are written to `files/results.json` and as one stdout line
`ONPREDRAW PASS|FAIL ...`.

## Recipe

```sh
# 1. build the lane (host wrapper), then the probe APK (inside atl_dev)
python3 /home/morten/.omp/profiles/atl-parallel/agent/resources.py --team vlc --kind build --timeout 3000 -- \
  toolbox run --container atl_dev sh -lc "ATL_WORKSPACE=$L/env ATL_PREFIX=$L/prefix \
    ATL_BUILD_DIR=/home/morten/ATL/parallel-apps/vlc/repo-followup/build-mobile JOBS=2 \
    sh /home/morten/ATL/parallel-apps/vlc/repo-followup/scripts/mobile/build.sh"

python3 /home/morten/.omp/profiles/atl-parallel/agent/resources.py --team vlc --kind build --timeout 1200 -- \
  toolbox run --container atl_dev sh -lc "sh <repo>/tests/on-pre-draw/build.sh <repo>/build-mobile <OUT>/apk"

# 2. screenshot run: the cancelled shot must NOT contain #FF00FF, the allowed one MUST
python3 /home/morten/.omp/profiles/atl-parallel/agent/resources.py --team vlc --kind atl --timeout 500 -- \
  env ATL_LANE_ROOT=/home/morten/ATL/parallel-apps/followup ATL_LANE=vlc \
    ATL_UI_SCRIPT='wait:2;shot:cancelled;wait:5;shot:allowed' \
    sh /home/morten/ATL/parallel-apps/tools/run-atl-probe-lane.sh <OUT>/apk/on-pre-draw.apk <OUT>/atl-run1 ONPREDRAW 300 ui

# 3. window-close coverage: same APK, listener still registered when the toplevel goes away
python3 .../resources.py --team vlc --kind atl --timeout 500 -- \
  env ATL_LANE_ROOT=/home/morten/ATL/parallel-apps/followup ATL_LANE=vlc \
    ATL_UI_SCRIPT='wait:2;shot:cancelled;wait:5;shot:allowed;wait:2;close:' \
    sh .../run-atl-probe-lane.sh <OUT>/apk/on-pre-draw.apk <OUT>/atl-run2 ONPREDRAW 300 ui
```

Judge the PNGs by pixels (e.g. python/PIL: count `(255,0,255)` and non-black
pixels), not by callback counts; check `ONPREDRAW PASS` and a fresh
`$L/data/apps/on-pre-draw.apk_/files/results.json`; a `close:` run must show
`ATL_UI_CLOSE atl_exited=yes` with no native crash.

## Status of this lane's self-check (2026-10-13)

Built (native exit 0, `libtranslation_layer_main.so` and `hax.jar` rebuilt) and
the probe APK ran under ATL (`ATL-PROBE STATUS=OK exit=0 mode=ui`, 2 shots,
`.../out-onpredraw/atl-run1`). The pixel evidence is **not** yet as required:
both shots are byte-identical, contain **0** magenta pixels and the probe had
not written `results.json` when the 9 s run ended. The registration native was
reached (`bionic_dlsym(... native_1set_1have_1predraw_1listeners) called`).
Open hypothesis: while a frame is cancelled the deferred
`g_idle_add_full(... queue_draw)` does not actually schedule a new frame for the
toplevel in this GTK build, so the cancel loop stalls (colour never appears, no
further dispatch). Next action: instrument dispatch counts and, if confirmed,
request the frame explicitly (`gdk_frame_clock_request_phase(clock,
GDK_FRAME_CLOCK_PHASE_PAINT)`) or queue the draw on the content widget instead of
the toplevel.
