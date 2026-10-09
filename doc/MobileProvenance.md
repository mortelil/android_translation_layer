# Source provenance for the experimental branch

The starting ATL commit is `f9e2280c1b78174c0536e02f246276cd80284c82`.
Original Git history, notices and LICENSE.txt are retained. Modified existing
AOSP-derived files retain their copyright/license headers. No new complete AOSP
class was imported during this work.

An earlier source-only review could not establish the origins of 14 new classes.
A later review recovered their original inline file-creation operations in the
local development record. Twelve were written through inline patches; ImageReader
and ATLMediaIndex were written through inline shell heredocs. These are generated
partial implementations of Android APIs, not recorded downloads of AOSP classes.
The initial writes are recorded below; later revisions implement the ATL behavior
in the current source. Private conversation/debug logs are not distributed.

| New source file | Initial write (UTC) | License in this fork |
|---|---|---|
| `src/api-impl/android/atl/ATLMediaIndex.java` | 2026-10-08T00:17:02.328Z | GPL-3.0-only |
| `src/api-impl/android/media/AudioRouting.java` | 2026-10-07T23:23:22.682Z | GPL-3.0-only |
| `src/api-impl/android/media/Image.java` | 2026-10-07T21:56:15.138Z | GPL-3.0-only |
| `src/api-impl/android/media/ImageReader.java` | 2026-10-07T23:31:14.697Z | GPL-3.0-only |
| `src/api-impl/android/security/NetworkSecurityPolicy.java` | 2026-10-07T22:47:05.033Z | GPL-3.0-only |
| `src/api-impl/android/text/DynamicLayout.java` | 2026-10-07T22:32:24.295Z | GPL-3.0-only |
| `src/api-impl/android/util/Range.java` | 2026-10-07T23:13:25.208Z | GPL-3.0-only |
| `src/api-impl/android/view/accessibility/AccessibilityEvent.java` | 2026-10-07T22:08:41.014Z | GPL-3.0-only |
| `src/api-impl/android/view/accessibility/AccessibilityRecord.java` | 2026-10-07T22:08:41.014Z | GPL-3.0-only |
| `src/api-impl/android/view/inputmethod/ExtractedText.java` | 2026-10-07T22:30:16.895Z | GPL-3.0-only |
| `src/api-impl/android/view/textservice/SentenceSuggestionsInfo.java` | 2026-10-07T22:07:23.852Z | GPL-3.0-only |
| `src/api-impl/android/view/textservice/SpellCheckerSession.java` | 2026-10-07T22:07:23.852Z | GPL-3.0-only |
| `src/api-impl/android/view/textservice/SuggestionsInfo.java` | 2026-10-07T22:07:23.852Z | GPL-3.0-only |
| `src/api-impl/android/view/textservice/TextInfo.java` | 2026-10-07T22:07:23.852Z | GPL-3.0-only |

The new native SurfaceTexture backend, test code and build/launch scripts are
also authored for this work with AI assistance and marked GPL-3.0-only. Generated
JNI headers derive from the Java API declarations. Existing files keep their
original notices and licenses; a class name/API signature is not an attribution
of its implementation to AOSP.

For any future direct AOSP import: retain the full license/copyright header,
record repository, source path and exact source commit in the commit message,
format the original with ATL's custom formatter, then document adaptations.
Do not assign a guessed AOSP origin to generated code.

The Matrix API contract was checked against AOSP frameworks/base commit
`299fe6f5d6fc6f1af7c3411dcf4e5efdf7217368`,
`graphics/java/android/graphics/Matrix.java`. This is a reference, not a source
import. Its native implementation copies a Graphene matrix using the operation
already present in ATL's native_set function.

The two companion repositories retain their own file-level licensing. New
bionic atfork/test code and the ART native test use Apache-2.0. Changes to ART's
existing WolfSSL file retain wolfSSL's GPL-2.0-or-later notice. No licensing
claim here covers APKs or other software supplied separately by a user.

AI assistance is disclosed throughout. These source records and passing tests
are not independent expert review or proof of complete Android behavior.

## Plexamp experiment (2026-10-09, not a completed compatibility claim)

The CPU HardwareBuffer and ALSA callback-input AAudio backends, HTTP cookie JNI
adapter, optional NFC type declarations, core-Java build script and associated
tests were authored for this fork with AI assistance. No AOSP implementation was
copied for these additions. The cookie adapter delegates parsing and persistence
to the installed libsoup library, rather than copying its source. API references
and implementation limits are recorded in MobileFork.md and the test READMEs.
D8 is downloaded separately from Google's distribution and is not vendored.
Plexamp APK files, decompiler output, personal app data and private diagnostic
logs are not part of the source distribution.

The subsequent EditText bridge, basic text-span/Pango attributes, NetworkInfo
state values, full-keyboard listener, ViewTreeObserver/Canvas scope fixes and
JobInfo metadata/tests were likewise authored for this fork with AI assistance.
Existing AOSP notices in files such as Layout and PasswordTransformationMethod
are retained; no new AOSP implementation was imported for these changes.
The native-density integration preserves the patch developed with ATL Shelf,
recorded in `mortelil/atl-shelf` commit `62ee446`. This work does not replace the
upstream Signal contribution: Julian Winkler's MR !325 and its original commit
history remain credited separately in this fork.
