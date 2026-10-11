# Additional integration contracts

SVC-018 is exercised by the existing probe pump and `verify_services.py --scenarios SVC-018`.
Use an identical newly built APK on Android and ATL. It asserts queued Fragment
commit, -1 without back stack, duplicate commit rejection; distinct per-intent
binders; stopSelf while bound; reusing an unbound connection; binder replacement;
context-scoped dispatchers; and unbind across multiple service components.
Do not compare a rebuilt APK to an old Android reference.

The preserved pre-repair runtime fails these assertions. Existing SVC-002..017
are also rerun using their frozen APK/reference, excluding SVC-014 from **parity**:
remote/isolated processes are unsupported and now explicitly rejected rather
than quietly created in the caller's process. Test this rejection separately.
No remote-service or process-restart support is claimed. Foreground notification
policy, service death/restart and cross-package IPC remain outside this layer.

Contract sources: AOSP frameworks-base commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`, particularly
`core/java/android/app/LoadedApk.java` (`ServiceDispatcher.doConnected`,
`forgetServiceDispatcher`), `FragmentManager.java`, `BackStackRecord.java`,
and `services/core/java/com/android/server/am/ActiveServices.java`.
The integration repairs are original ATL implementations based on these contracts.
