row A06 (unit half) - window focus/visibility vs lifecycle state, executed

log: unit-focus-invariants.log (tests/activity/run-unit.sh, dalvikvm on the lane runtime)
markers:
  ACTLPATH ALL_DONE 191 PASS 0 FAIL   (A01/A02 regression, unchanged)
  ACTLFOCUS ALL_DONE 9 PASS 0 FAIL    (A06 checks below)

ACTLFOCUS checks (all PASS):
  empty-window-ready-no-op             windowReady() on an empty stack neither throws nor transitions
  host-event-window-ready-no-op        hostEvent(HOST_EVENT_WINDOW_READY) routes to the same no-op
  no-focus-after-empty-window-ready    hasWindowFocus(null)=false: no focus is invented without a record
  no-focus-state-name                  no lifecycle state name is a focus/window/visibility concept
  no-focus-in-lifecycle-paths          every path element over 43 valid pairs is a lifecycle state (21
                                       intermittent-endpoint pairs are guarded, covered by A01/A02)
  restart-not-a-transition-endpoint    ON_RESTART is refused as a start endpoint (intermittent, not a state
                                       a focus change could leave the activity in)
  record-focus-state-distinct          ATLActivityRecord.getState():int vs hasWindowFocus():boolean
  record-accessors-same-record         both accessors belong to the same record class
  state-space-closed                   the state value space is exactly the lifecycle constants

run-unit.sh now decides on the ALL_DONE markers instead of dalvikvm exit code:
dalvikvm aborts in its AsynchronousCloseMonitor teardown after the test main has
finished (positional, after ALL_DONE); the gate is PASS 0 FAIL on both markers.
