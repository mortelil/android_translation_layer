# lifecycle-probe v2.1 — contract table (LC + FT)

Pinned source of truth: AOSP `platform/frameworks/base`, tag
`refs/tags/android-17.0.0_r1`, peeled commit
**`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`**. Local pinned copies:
`~/ATL/reference/aosp/android-17.0.0_r1/frameworks-base/core/java/android/app/`.
Line numbers below are for those pinned files. Every expected value in
`manifest.json` is derived from these methods, not from any observed run.

v2.1 changes vs v2: (a) **L-THROW is a TERMINAL scenario** — it runs *after* the
aggregate + `LIFE_ALL_DONE`, because on Android the framework kills the process
there (see the note below), so it must not be able to destroy the evidence that
precedes it; (b) fragment identity is carried in a probe-only `Fragment` subclass
field, **not** via `Fragment.setArguments`/`getArguments` (not implemented by
ATL and unrelated to the contract under test); (c) the LC block is
L-SNAP, L-REGD, L-EQUALS (L-THROW moved out of the LC block).

The probe is one APK / one process launch with no intent extras (ATL `run.sh`
cannot pass extras), running a fixed ordered scenario script. All events carry
`scenario, seq, actor, event, activity, bundle, phase, owner, thread, threadRole,
main`. Completion signal: sentinel `LIFE_ALL_DONE` in stdout (+ `files/results.json`
plus per-event `LIFE_EVT` stream and `LIFE_SCEN_BEGIN/END`). The terminal scenario
additionally emits the control line `LIFE_PROBE_MARK TERMINAL_THROW_DONE` after
its required prefix; a runner may poll for that string instead of waiting for the
app to exit (ATL GUI apps do not self-exit).

## Application.ActivityLifecycleCallbacks (LC)

| Operation | Inputs / state | Result / order / error | AOSP source (file:method) | Scenario id |
| --- | --- | --- | --- | --- |
| register | single callback | appended to `mActivityLifecycleCallbacks` (`ArrayList.add`) | `Application.java:registerActivityLifecycleCallbacks` (310-314) | INIT, S4.2/S4.4/S4.5, L-REGD |
| unregister | one occurrence | `ArrayList.remove(o)` → removes first element `equals` to `o`; no-op if absent | `Application.java:unregisterActivityLifecycleCallbacks` (316-320) | S4.2, S4.4, S4.5 |
| dispatch order | snapshot of list | forward `i=0..n-1`, all seven callbacks, outside the lock | `Application.java:dispatchActivity*` (e.g. dispatchActivityResumed 470-478, dispatchActivityDestroyed 578-586), `collectActivityLifecycleCallbacks` (608-616) | S1, S2, S4.x |
| duplicate registration | same instance N times | additive: invoked N times per event | `Application.dispatchActivity*` + `add` | S4.3 |
| unregister during dispatch | callback removes a listed callback | removed callback still runs for the in-flight event (private `toArray()` snapshot); absent from next event | `collectActivityLifecycleCallbacks` (608-616) | L-SNAP |
| register during dispatch | callback adds a callback | new callback not in the in-flight snapshot; runs from the next event | `collectActivityLifecycleCallbacks` (608-616) | L-REGD |
| observer throws | first listed callback throws | dispatch does not catch; remaining callbacks for that event are NOT invoked; exception propagates out of the enclosing `super.*()` | `Application.dispatchActivity*` (plain `for`, no try) | L-THROW |
| equals-overriding removal | `unregister(E2)`, `E2.equals(E1)` | `ArrayList.remove` uses `E2.equals(element)`, so `E1` is removed | `Application.java:316-320` + `java.util.ArrayList.remove` | L-EQUALS |
| early finish | app calls `finish()` while resumed | normal pause/stop/destroy, one event each | `Activity.java:onPause/onStop/onDestroy` dispatch sites (2721,2952,3030) | S3D1 |
| callback placement | app override calls `super.*()` | callback fires **inside** `super`, i.e. between the app's `pre`/`post` lines | `Activity.java:1915,2208,2284,...` | every LC scenario (`.pre`/`.post` framing) |
| Bundle | fresh create | `onActivityCreated` receives `null` | `Application.java:dispatchActivityCreated` | S1..S4.x |
| thread | all `perform*` | main thread; `main=true`, `threadRole=main` | `Activity.perform*` → ActivityThread main | every event |

## FragmentManager tags (FT, `android.app`)

| Operation | Inputs / state | Result / order / error | AOSP source (file:method) | Scenario id |
| --- | --- | --- | --- | --- |
| add + tag | `add(f,"t")`, `commit`, `executePendingTransactions` | fragment added to `mAdded`; `mTag` set at `add()` time | `FragmentManager.java:addFragment` (1710-1731); `BackStackRecord.doAddOp` (423-442) | F-ADD-TAG |
| find by tag | added fragment | returns the fragment (searches `mAdded` then `mActive`) | `FragmentManager.java:findFragmentByTag` (1847-1866) | F-ADD-TAG, F-TAG-READ |
| duplicate tags | two added fragments, same tag | reverse scan → **newest-added** returned | `FragmentManager.java:findFragmentByTag` (1849-1856, `i=size()-1..0`) | F-DUP-TAG |
| null / missing tag | `findFragmentByTag(null)`, unknown tag | `null` (null skips the added scan; unknown matches nothing) | `FragmentManager.java:findFragmentByTag` (1848,1861) | F-NULL-MISS |
| lookup before commit | `add()` op queued, not executed | not yet in `mAdded` → `null`; after `executePendingTransactions` → hit | `FragmentManager.java:addFragment` (only on execution); `BackStackRecord.executeOps` | F-PRE-COMMIT |
| remove then lookup | `remove(f)`, `commit`, `executePendingTransactions` | removed from `mAdded`; destroy path → `makeInactive`/`burpActive` purges `mActive` → `null` | `FragmentManager.java:removeFragment` (1734-1752), `makeInactive` (1697-1708), `burpActive` (2949-2958) | F-REMOVE |
| same-fragment tag mutation after add | change `mTag` on an added fragment | **UNSUPPORTED**: no public mutator; `Fragment` exposes only `getTag()` (647-649), `BackStackRecord.doAddOp` (434-436) throws `IllegalStateException` on a tag conflict | `Fragment.java` (no `setTag`); `BackStackRecord.doAddOp` | — (documented) |

### Notes / stop-condition items

- **L-THROW is TERMINAL.** The observer throw escapes the app's
  `super.onCreate()` because `Activity.dispatchActivityCreated`
  (`Activity.java:1589-1598`, invoked at `Activity.java:1915`) runs **before**
  `mCalled = true` (`Activity.java:1920`) and does not catch. The throw is
  caught by the probe override so it can record `onCreate.caught`/`onCreate.post`,
  **but the framework then checks `mCalled`**: `ActivityThread.performLaunchActivity`
  sets `mCalled=false` before `onCreate` (`ActivityThread.java:4550`) and raises
  `SuperNotCalledException` when it is still false (`ActivityThread.java:4561-4564`),
  which is fatal. (v2's claim that "`performCreate` does not check `mCalled`" was
  wrong: the check lives in `ActivityThread.performLaunchActivity`.) The scenario
  therefore runs **last**, after `LIFE_JSON_END`/`LIFE_ALL_DONE`, and its accepted
  evidence is the ordered prefix up to (and including) the throwing observer's
  callback plus the absence of the later callback; the trace may legitimately end
  there on Android.
- **Fragment identity** for the FT matrix is a probe-only `MarkedFragment extends
  Fragment` carrying an `int`; `Fragment.setArguments`/`getArguments` are **not**
  used (ATL does not implement them and they are not part of the `FragmentManager`
  contract under test). Identity is still never a raw address. The class **must be
  `public static`** (and have a public no-arg constructor for state recreation):
  `BackStackRecord.doAddOp` (`BackStackRecord.java:423-431`) throws
  `IllegalStateException("<fragment class> must be a public static class to be
  properly recreated from instance state.")` for anonymous, non-public or
  non-static fragment classes — observed on the API 37 emulator with a
  package-private nested class.
- **Result transport**: the per-event `LIFE_EVT`/`LIFE_SCEN_*`/`LIFE_PROBE_MARK`
  lines are small and reliable on stdout, but the single aggregate-JSON line
  between `LIFE_JSON_BEGIN`/`LIFE_JSON_END` exceeds the Android logcat per-entry
  limit (~4 KiB) and is **truncated/dropped** when stdout is captured via
  `logcat`. A runner MUST take the aggregate from `files/results.json`, not from
  the stdout JSON line (the emulator run of 2026-10-10 confirms this: the line was
  absent from `logcat -s System.out:*` while `results.json` was complete).
- **F-REMOVE** rests on the destroy path purging `mActive`; if the emulator build
  keeps the fragment in `mActive`, the runner will see the reference reject —
  flagged here rather than faked.