# Creator-owned PendingIntent

Build and run inside the ATL development container, with ATL_BUILD_DIR,
ATL_PREFIX and ATL_CORE_JAR pointing at the candidate runtime:

```
sh tests/pending-intent-owner/build.sh "$ATL_BUILD_DIR" /tmp/pending-apks
sh tests/pending-intent-owner/run.sh /tmp/pending-apks /tmp/pending-results
```

The caller obtains a token from a different APK's real service process. It checks
creator identity, actual broadcast delivery in that process, immutable fill-in,
shared cancellation and creator death. Local rows cover parcel identity, deep
snapshotting (including an application-defined Parcelable), update/no-create,
cancel-current, one-shot, mutable extras, IntentSender, categories/package in
identity, conflicting flags and an invalid parcel handle. A PASS requires actual
receiver observations, not just successful send(). The timeout fails the test.

The notification APK uses a native helper to activate real GApplication actions.
It checks delivery, replacement without accumulating actions, cancellation, and
cancelAll removing retained actions. It does not test desktop notification UI.
It requires ATL and is not an Android APK because the helper links GLib.

The owner/caller APKs can also be signed and installed on Android. Start
org.atl.pendingprobe/.Caller and inspect the PendingProbe log tag. The deliberate
platform difference is creator death: Android system-server retains the token;
ATL currently returns CanceledException because its owner process is gone.

## Contract and boundaries

Reference: AOSP frameworks/base commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/app/PendingIntent.java,
core/java/android/content/Intent.java (fillIn), core/java/android/content/IIntentSender.aidl
and services/core/java/com/android/server/am/PendingIntentRecord.java.
Intent fill-in logic is adapted with its Apache 2.0 notice; Binder transport and
GTK action integration are ATL implementations.

This fixes ownership during transfer, not Android system-server persistence or
an OS security boundary. Tokens are capabilities routed through ATL Binder RPC.
Records and app contexts survive while referenced, but not creator-process exit.
Activities/services use the same owner-context dispatch; this fixture verifies
broadcast delivery, not every activity/task or foreground-service lifecycle.

Multiple-activity stacks, nonempty launch options, ClipData fill-in and
IntentSender completion callbacks remain explicitly unsupported. Return codes
for ordered broadcasts are not implemented. The eager Parcel codec requires
custom Parcelable classes to be available to the owning application's loader.
Notification IDs still use ATL's existing desktop namespace. No claim of full
PendingIntent or notification API coverage is made.
