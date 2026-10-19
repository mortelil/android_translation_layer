# Remaining compatibility contracts

In atl_dev with ATL_BUILD_DIR, ATL_PREFIX and ATL_CORE_JAR set:

    sh tests/remaining-contracts/run.sh /tmp/remaining-contracts

The 40 checks use a synthetic private profile. They cover constructor-safe View
state, ContextWrapper identity delegation, frame timing data and copy ownership,
process-info defaults, public directory creation/writability, and explicit
unavailable notification/network-statistics behavior. No real accounts or media.

FrameMetrics is an AOSP-derived data object; ATL does not produce frame timing
callbacks. Network statistics queries return unavailable, and usage callback
registration throws rather than claiming a working monitor. Notification listeners
remain unconnected. These are compatibility surfaces, not new OS backends.

This integrates OMP 2debd3f7, 6547076c, 5096d1ac, 4739138f, 5aa59bef,
de9ebd87, 42304972 and the native hover-coordinate portion of 3866cce7.
Corrections include actual FIRST_DRAW_FRAME/DEADLINE semantics, separate
instance/process UID values, explicit usage-monitor failure, directory retry,
and explicit Meson source registration. AOSP reference: frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d and SDK android-37.0 sources.
