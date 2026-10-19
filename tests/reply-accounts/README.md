# Reply headers and empty-account listener integration

Build in atl_dev: `sh tests/reply-accounts/build.sh build-mobile OUTPUT`.
Sign OUTPUT/probe.apk with a development key, install on an Android emulator,
and launch org.atl.replyaccounts/.Probe. Run the same signed APK with ATL.
Success: `REPLY_ACCOUNTS_PASS checks=35`. No login or account writes are used.

Checks cover integer success headers, raw API-written replies, eight exception
categories with messages/service codes and payload position, asynchronous
listener notification on the supplied handler, duplicate registration,
removal/re-registration and updateImmediately=false. Android API37 and ATL
x86_64 passed the same APK. Existing tests/transport also passed against the
candidate, including remote exceptions and failed/unsupported transactions.

Scope: ATL's store remains empty; account persistence, authentication, visibility
policy and cross-process account-change broadcasts are not implemented. The
account-type filter is vacuous for the empty store. The listener API must evolve
with any real store implementation.

Parcel now follows Android exception-header semantics, but its primitive/string
encoding remains ATL-specific, not native Android byte-for-byte compatibility.
StrictMode metadata is skipped with bounds checks, not processed. AppOps reply
metadata and arbitrary Parcelable exception types remain unsupported explicitly.
Empty replies must not be accepted as success. The internal protocol change
requires both sides of an ATL IPC session to use the same runtime version.

References: AOSP frameworks/base android-17.0.0_r1 Parcel.java and AccountManager
listener API. Implementations/tests are original, AI-assisted code.

ReVanced integration: missing OnAccountsUpdateListener/Glide failure disappeared;
window/navigation bar rendered. FlagStore still reads an empty reply and throws
ParcelFormatException; fixing the header format does not fix that separate
missing-reply problem. Feed/search/playback are not verified working.
