# Queued activity launch

Build with `sh tests/queued-launch/build.sh BUILD OUTPUT`, sign, then launch.
Require QUEUED_LAUNCH PASS and results.json. A -> B is requested in onCreate;
B -> C is requested in onPostCreate. Targets must not enter creation before
the caller returns, must run on the main looper, and A/B must not resume over C.
C leaves a visible FINAL ACTIVITY screen. No account or user data.

The old inline runOnUiThread path fails with "target entered before caller
completed creation". AOSP reference: frameworks/base android-15.0.0_r1,
ActivityThread.ApplicationThread.scheduleTransaction and H.EXECUTE_TRANSACTION.
This change queues ATL's existing native launcher, rather than importing
Android's system-server transaction stack. Derived from OMP VLC a74d777's
launch deferral, without its diagnostic logging or unrelated Fragment changes.

This does not repair existing task/launch-mode/result-delivery semantics.
