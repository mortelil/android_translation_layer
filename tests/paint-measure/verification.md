# Paint result-rounding verification (2026-10-10)

Baseline main: b3f700ecc03d39fc7f089254960da8c9bf09f41a.
Both x86 builds configured separately from that source and compiled in atl_dev.
Only changed class in hax.jar: android/graphics/Paint.class (zip-entry byte diff).
Baseline api-impl.jar: 60a10f6b2fc193595bcdb3c8ba4d9471427440fe2f9e5f9b445077653c8ad679.
Candidate api-impl.jar: ca6860b372f8476ea95e52817dd735c0ba37fba63331a9f34e27e08b99394922.
On ARM, native baseline built from git archive of the same main, then copied into
an isolated candidate directory with the identical candidate Java DEX jar.
Process maps verify candidate JAR/OAT and native paths; no installed runtime replaced.

| Test | Baseline | Candidate |
|---|---|---|
| Public Paint probe, 64 cases x 4 overloads | FAIL fractional space width 2.3046875 | PASS x86; PASS ARM |
| Same APK on Android API 37 | reference PASS | same public contract |
| mobile-smoke (x86) | PASS | PASS |
| FONT-001/002 vs Android | existing width/bounds/metrics failures | same failing fields; all final widths now integral |
| Immich PC | photo opening/back/library/scroll responds | photo/library captures byte-identical; scroll responds |
| Immich Nura | photo opening/back/library responds | same visually verified behavior |
| Plexamp Nura | welcome->login; insertion/deletion/masking | same; helper confirms text survives JS updates |
| Plexamp PC | N/A: no x86 APK | N/A |
| Messenger PC | email and language selector work; later getNotification NoSuchMethodError | same error, email and language selector work |
| Messenger Nura | login visible, synthetic input/delete/masking PASS | same |
| LocalSend PC/Nura | receive/send/settings respond | same; synthetic text arrives on Nura |

Limitations are not passes: PC Messenger password retest blocked by the identical
Notification.Builder.getNotification background failure in both builds. Nura
physical OSK was not exercised (input helper acts on mapped GtkText fields).
Plexamp icon-font tofu persists. No actual app login was submitted. Immich uses
previously authorized private copies, empty local media and no uploads or edits.
Video playback and LocalSend reverse file transfer were not retested. LocalSend
file picker did not complete under this constrained Xvfb session; not counted as
passed. Its first baseline launch SIGILLs in libutils MessageHandler destructor;
a fresh baseline repetition starts. An additional port-in-use attempt was caused
by concurrent test instances and discarded, not classified as an app regression.

FONT numerical differences are explicit in font-comparison.txt. For example,
WWWW is 252 on Android, 253.125 on baseline and 254 after public ceiling. That
native-width error already exceeded the original 1px threshold before the patch;
no newly failing field is relabeled as passed. The native heuristic fix stays out.

Private screenshots, appdata and full logs remain outside Git. Only source,
portable tests and a sanitized result summary may be published.
