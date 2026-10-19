# Service ownership regression

Build inside Alpine: `sh tests/service-owner/build.sh BUILD_DIR OUTPUT_DIR`.
Sign owner.apk and caller.apk with an Android test key. Run inside Alpine with
ATL_PREFIX, ATL_CORE_JAR and ATL_BUILD_DIR selecting the candidate runtime:
`sh tests/service-owner/run.sh OUTPUT_DIR RESULTS_DIR`.
Use a new RESULTS_DIR (it contains an isolated profile).

The owner APK declares an absolute custom process name, unrelated to its
package name. A different caller APK binds to it, checks the returned owner,
process and distinct PID, then unbinds through the caller Context. A wrong APK,
a local service or a registration kept in the wrong controller fails the test.
No user app data or accounts are used.

`tests/service` also runs OwnerContracts: distinct per-app hosts, explicit APK
selection, process fallback and forwarded reconnect/binding-died/refusal events.
The synthetic owners preserve the fake backend's legacy global kill reference.

Scope: ordinary declared service processes, including an installed companion
APK. This does not implement Android UID isolation, isolated services, arbitrary
third-process Binder proxy relaying or split APK service hosts. Those paths must
fail explicitly; this is not full Android service compatibility.

The owner also has a launchable ForwardActivity. Its child binds back to a
loader-resident ParentService during onCreate, checks the parent's PID over
Binder, unbinds, and publishes the verified result back to the activity.
The driver requires CHILD_TO_PARENT_PASS as well as CROSS_PACKAGE_SERVICE_PASS.
