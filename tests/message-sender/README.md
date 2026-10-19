# Messenger sender identity

Build: `sh tests/message-sender/build.sh build-mobile OUTPUT`. Sign the probe APK
and run `org.atl.messagesender/.Probe` on Android and the same APK in ATL with a
private profile. Expect `MESSAGE_SENDER_PASS checks=11`.

Tests default -1, obtain/copyFrom, recycling, Parcelable round-trip, a forged
local sender UID, and a real separate-process service with a Messenger callback.
Both request and response contain forged UIDs which receipt must overwrite.
The different service PID assertion prevents a same-process false pass.

Adapted from AOSP frameworks/base android-17.0.0_r1 Message.java and
Handler.MessengerImpl.send (source commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d); existing Apache-2.0 headers retained. ATL transport
currently authenticates the Linux peer and uses the shared host UID; this does
not implement per-package Android UID isolation. Message's ATL wire format now
includes sendingUid, so communicating processes must use the same runtime.
