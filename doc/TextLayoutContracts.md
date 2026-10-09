# Text layout line contracts: first milestone

This work is on `text-layout-contracts`, separate from Messenger verification.
The phase-1 publication gate is still open; this branch must not be included in
that publication merely because it starts from the Messenger candidate.

## Choice and evidence

Text layout has reproducible failures with small, deterministic inputs. Signal's
Compose registration field calls `getLineBottom(-1)` for an empty line; ATL
throws, while Android derives this boundary from line top zero. Native ATL also
counts Unicode code points for line starts and passes Java offsets directly to
Pango's byte-index API. These disagree with Android's UTF-16 indexing for both
non-ASCII text and supplementary characters. Messenger has already exposed text
measurement and font integration gaps. Correct queries benefit ordinary Android
widgets and Compose, not only these applications.

Service lifecycle and notifications were also inspected: service stop methods
lack real lifecycle transitions; notification tags/channels/cancelAll lack
complete behavior. Both require broader ownership and Linux integration work.
They remain valuable later milestones, but neither provides as small a first
contract boundary as line queries. No claim of completeness is made for them.

## Scope

* `getLineStart`, `getLineEnd`, `getLineForOffset`: UTF-16 offsets, newlines,
  wrapped lines, empty text and trailing empty lines. End includes the line break.
* The extra `getLineStart(lineCount)` entry equals the text length.
* `getLineTop(lineCount)` equals total height; adjacent bottom/top boundaries
  agree. The predecessor `getLineBottom(-1)` is zero, consistent with Android's
  next-line-top definition used by Compose. Other out-of-range line accesses
  remain checked.
* Queries follow `DynamicLayout` edits and its display sequence.

Excluded: full StaticLayout builder behavior, Android-identical line breaking
or font metrics, line-spacing/padding configuration, ellipsis, bidi cursor/hit
testing, selection paths, full visible-end whitespace trimming, embedded NUL and malformed surrogate handling. Existing
stubs in these areas are not made correct or advertised by this milestone.

## Implementation and proof

1. Add APK contract fixtures and record failure before implementation.
2. Convert Pango byte positions to UTF-16 units for line queries. Keep Pango as
   the Linux shaping/layout backend; avoid importing Android's Minikin stack.
3. Implement sentinel/adjacency semantics at the Java/native boundary.
4. Test empty text, hard breaks, wrapping, emoji/surrogate-pair offsets, combining
   marks, RTL text boundaries, out-of-range offsets and mutable display text.
5. Run the existing APK suite, then Signal registration and the four other app
   UI regressions. Contract success alone does not prove application support.

Reference: [AOSP Layout.java, android-15.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/text/Layout.java),
specifically getLineForOffset, getLineStart/Top documentation, getLineEnd and
getLineBottom. The implementation is a Pango adaptation of these contracts, not
a copied AOSP class. If later work imports AOSP code, retain its Apache-2.0
header and record the exact source commit in the importing commit.

## Initial results

The new test first failed on `getLineBottom(-1)` with the original candidate.
After the implementation, the complete PC and Nura ARM APK contract suites pass, including
the new UTF-16/line/sentinel fixtures. The visible-end query still excludes
paragraph breaks and now counts supplementary characters correctly; its wider
Android whitespace rules remain outside this milestone.

Signal's PC phone-registration page now renders instead of exiting in Layout.
Typing a fictitious number updates its formatting and enables Next. Repeated
Backspace deletes characters and disables Next for an incomplete number. Full
clearing was not verified before the test harness expired; Ctrl+A did not select
the field contents in this run. No Next/SMS action was taken. The remaining app regressions have not yet been repeated with phase-2 code,
so this is not a release-ready claim.
