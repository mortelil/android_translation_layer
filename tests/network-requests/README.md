# Host-default network requests

Build with `sh tests/network-requests/build.sh BUILD_DIR OUTPUT_DIR`, sign the
APK, launch org.atl.networkrequests/.Probe and require NETWORK_REQUESTS PASS.
The probe overrides availability/metering in a ConnectivityManager subclass
and delivers snapshots through the registered adapter using reflection. It
still registers/unregisters the real native subscriptions, but never changes
host networking. This is a deterministic ATL state-machine test, not an Android
emulator equivalence test or proof of physical transport detection.

Checks initial available -> capabilities ordering, requested Handler dispatch,
duplicate suppression, capability-copy isolation, metering changes, loss and
reconnection, no timeout after an already fulfilled request, pending callback
suppression on unregister, reentrant unregister and unsupported transport
expiry with automatic release. Real host connectivity is covered by app replay.

The implementation replaces the callback/request portion of OMP VLC a74d777
rather than copying its immediate availability/unavailability and timeout logic.
Contract reference: AOSP packages/modules/Connectivity, android-15.0.0_r1,
framework/src/android/net/ConnectivityManager.java, NetworkCallback and timed
requestNetwork documentation.

Scope: one virtual host-default network, backed by GNetworkMonitor availability
and metering. Requests observe and retain interest in that network; ATL cannot
activate a separate Wi-Fi/cellular/VPN bearer. Physical transports, validated
Internet reachability and LinkProperties are unknown and are not fabricated.
Transport-specific requests remain unmatched. This is not a full Android
ConnectivityService. Legacy NetworkInfo reporting is unchanged.
