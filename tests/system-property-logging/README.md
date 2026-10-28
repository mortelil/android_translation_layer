# System property read diagnostics

Run `sh tests/system-property-logging/run.sh` inside the Alpine development
container. The test compiles the actual SystemProperties source against minimal
Build/Log fixtures. It checks string, boolean, integer, long and SDK lookups,
then 10,000 repeated reads. Normal operation makes zero logging calls; setting
`ATL_DEBUG_SYSTEM_PROPERTIES=1` restores diagnostics for every read. `0` leaves
logging disabled. Values and existing lookup semantics are unchanged.

This is a test of logging overhead and preservation of existing values, not a
claim of complete Android SystemProperties compatibility. It does not measure
whole-app startup or rendering speed. Real-app validation should additionally
check both modes with the compiled ATL runtime. Warnings and exceptions are not
suppressed by this switch.
