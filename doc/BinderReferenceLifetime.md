# ATL Binder reference lifetime

ATL's process transport uses Unix sequenced-packet sockets, not the Android
kernel Binder driver. The lifetime contract follows Android's strong Binder
references: keeping a proxy or a Parcel containing it keeps the exported Binder
available. Unbinding a service does not invalidate a proxy retained by app code.
The AOSP behavioral reference is frameworks/base revision
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` (IBinder/Binder/Parcel); the transport
implementation is original ATL code, GPL-3.0-only.

## Wire version 2

The magic is `0x41544c32`; old peers are rejected. ATL launches children using
its own executable/runtime, so update the parent and children together (restart
running apps). This is not a stable external IPC protocol.

Each positive Binder-table entry in a CALL or REPLY grants one reference credit.
The owner reserves it before sending; the receiver records it before dispatching
the call or waking the caller. There is no separate ACQUIRE frame. That old
handshake allowed callers to release a proxy before the reader processed ACQUIRE.
Negative entries return the peer's own objects and do not grant new credits.

The proxy cache holds weak references. App references, unrecycled Parcels and
active calls hold proxies strongly. A reference-queue worker returns all credits
belonging to a collected proxy in RELEASE's `code` field. Repeated transfers add
credits; collecting an older proxy cannot release a newer proxy's credits for
the same handle. Sending a negative reference keeps its proxy alive until the
frame has been sent. Parcel recycling relinquishes its Java references as well
as owned file descriptors.

Export removal requires both zero credits and zero in-flight pins. RELEASE
acknowledgements delayed by active calls are emitted when those calls drain,
outside the object-table lock. The root has a permanent connection-owned credit.
Closing a connection clears its tables, wakes callers and notifies live proxies.
The package-private `drop` method is a deterministic test seam; production
cleanup does not require callers to invoke it.

The 4096-object limit is a live-table limit, not a lifetime allocation limit.
Handles increase monotonically; integer exhaustion fails explicitly rather than
reusing an old handle. Invalid release counts and obsolete acquisition frames
close the connection. Failed sends close the channel to discard partial credits.

## Tests and limits

`tests/transport` exercises production transport over real sockets. Its
`LifetimeProbe` covers immediate release after publication, an acknowledgement
blocked behind an active call, 4224 normal-API transfers without manual `drop`,
identity of repeated transfers, retention by app code and by a Parcel, and
reclamation after recycling. The independent results verifier requires the
`t11-lifetime-ownership` row. Existing tests cover concurrency, reentrancy,
oneway ordering, descriptors, peer death, malformed frames and load limits.

GC cleanup is eventual. The stress test explicitly requests collection with a
bounded deadline; this is not a promise of immediate production reclamation.
Cross-connection Binder forwarding remains explicitly unsupported. This change
does not implement Android's kernel Binder protocol, cross-package permissions,
or persistence after the ATL coordinator dies. It also does not establish ARM
coverage or real-app compatibility; those require separate integration tests.
