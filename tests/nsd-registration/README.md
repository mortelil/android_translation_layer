# DNS-SD registration

Run in atl_dev with ATL_BUILD_DIR/ATL_PREFIX/ATL_CORE_JAR identifying the build:

    sh tests/nsd-registration/run.sh /tmp/nsd-registration

Requires a reachable Avahi daemon for the positive arm. Advertises only synthetic
_atl-reg._tcp instances; does not open a server or connect to existing LAN apps.
Verifies two real advertisements, collision rename, caller snapshot isolation,
port and duplicate-listener validation, main-thread callbacks, discovery and
resolution (including binary, UTF-8, empty and bare TXT records), unregister and
observed withdrawal. Immediate cancellation must suppress stale registration
callbacks. A second process with an unreachable test D-Bus address verifies an
explicit failure callback instead of fake registration success.

OMP commits 156d5df5 and 37dc268a, corrected for registration cleanup, synchronous
collisions, caller mutation, empty TXT preservation and allocation failures.
AOSP API references are recorded in the implementation. This is the legacy
RegistrationListener API; executor overloads and Network-specific registration
are outside scope. Does not certify KDE Connect pairing or file transfer.
