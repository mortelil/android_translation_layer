# Messenger and text-layout regression verification

Date: 2026-10-09. This is an experimental compatibility checkpoint, not a
certification that these applications are fully supported.

Tested ATL implementation: `5264bb114355878047af676220d3fcb3fb5e0f24`
(including the separately developed line-layout milestone `018dd1e7`).
Bionic: `22eb732a64e6742ee6ab26f7fcd9855e0bfd8688`.
ART: `327b9c2e5ec49176c073df9f7995d7faf69153b5`.
The publication commit adds documentation only. Existing upstream authorship
and attribution remain in Git history; this fork's changes were developed with
Codex assistance. See [MobileFork.md](MobileFork.md).

## Environment and method

PC: x86_64 Alpine container on Linux Mint, private Xvfb display, Openbox and
xcompmgr. Nura: native aarch64 Alpine/Plasma Mobile, Wayland, render scale 1,
408x794 application surface. Both use the pinned source ART/Bionic runtime and
D8 core classes. Messenger also uses the optional apksig verifier. These are
explicit experimental runtime settings; the ordinary packaged-runtime install
was not certified. See [MessengerExperiment.md](MessengerExperiment.md).

PC editing uses X11 keyboard/mouse events. Nura navigation uses a test-only,
process-local helper dispatching Android touch events; Messenger/Plexamp field
checks operate on their real GTK editable children and allow JavaScript updates
to run. They verify app/widget behavior, **not physical touch routing or the
Plasma on-screen keyboard**. App-only captures were inspected after waking DPMS
on the unlocked phone. A script completing without visible output was not
counted as a passing input test.

Immich uses explicitly authorized private copies of existing sessions and normal
Secret Service access. The Nura copy has backup disabled and an empty local media
root. No real photo uploads, edits or deletion were performed. LocalSend uses a
62-byte disposable text fixture. No Messenger login, Signal SMS registration or
Plexamp login was submitted. Private logs and screenshots are excluded from Git.

## Application matrix

“Pass” below applies only to the named actions; the limitations column is part
of the result. Untested actions are not counted as passed.

| App | Platform | Tested functionality and result | Limits / unverified behavior |
|---|---|---|---|
| Messenger | PC | Pass: login layout; visible synthetic email; masked password; focus changes; complete deletion of both fields; language chooser renders. | Minor extra field outlines. No account login. |
| Messenger | Nura | Pass: login layout at phone scale; synthetic Unicode insertion/deletion, masking and focus changes survive JS updates; empty login focuses email; language chooser opens and closes. | Physical IME untested; extra corner outlines. |
| Immich | PC | Pass: authenticated timeline, full remote photo, Back, Library, drag scrolling and Search navigation. Earlier candidate check: video time advances and Back works. | Video pixels black in Xvfb on candidate **and published ATL 66c1d0fe** with the same runtime. Audio, local-media upload and rapid-scroll handle not verified. |
| Immich | Nura | Pass: authenticated timeline, full remote photo, Library, Search/Videos navigation; video frames change and playback controls show progress; Back returns to Videos. Final run uses process-local `GSK_RENDERER=gl`. | Audio and smooth frame cadence unverified. Renderer causality not established. Local media/backup and rapid-scroll handle not retested. |
| Signal | PC | Pass: welcome/permission flow reaches registration; fictitious number formats and enables Next; repeated Backspace clears it and disables Next. | Country selector did not open in this harness: unresolved, not passed or established as a regression. No SMS or messaging test. |
| Signal | Nura | Pass: welcome/permission navigation reaches registration; final build renders registration at correct scale without the previous Layout exception. | Number editing and physical IME untested on Nura. Country selector did not open under synthetic touch: unresolved. No SMS or messaging test. |
| Plexamp | PC | Not applicable: no x86 Android build; explicitly removed from scope by the user. | No emulation claim. |
| Plexamp | Nura | Pass: actual sign-in screen; synthetic email/password insertion, deletion, focus changes and masking; values survive JS updates without the old input crash. | Physical IME and actual login/playback untested. Some icon-font glyphs remain missing. |
| LocalSend | PC | Pass: native file chooser selects fixture, peer discovery, send to Nura, accept return transfer, both completion screens and matching SHA-256. | Default desktop portal path not covered. |
| LocalSend | Nura | Pass: select fixed fixture through real GTK chooser, send to PC, accept incoming file, completed UI and matching SHA-256 in both directions. | Test uses `GDK_DEBUG=no-portals`; GTK chooser too wide for phone. Default Plasma portal and physical chooser interaction unverified. |

Fixture SHA-256:
`409a1fb5b904ede36aebfbe8185a0586afcff34d7a09bf4c9a84f980e55d9fbe`.
The final reverse transfer produced a newly received file, not merely a match
against an old fixture. A stale PC test process caused a port conflict during a
restart; stopping that process restored the test without a code change.

## Contract and native tests

* Full PC `scripts/mobile/test.sh`: exit 0 with the final implementation.
  Includes APK contracts, Bionic loader/CFI/thread tests, ART Looper and alternate
  signal stack ownership, EGL multi-client lifetime, hardware/shared memory,
  AAudio with ALSA null output, and GTK text appearance pixel checks.
* Full ARM APK contract suite: passed, including UTF-16 offsets, line sentinel
  entries, paragraph boundaries, wrapped text, emoji and mutable layouts.
* ARM ART Looper and alternate-stack fixtures: passed.
* ARM Bionic thread destructors, ELF enumeration, RUNPATH and CFI fixtures:
  passed. Compiler-generated ARM CFI fixtures were cross-built on PC and run
  on Nura; the monolithic ARM native test script was not run end-to-end.
* Private ART build helper: incremental invocation verified on both platforms
  against existing source-build outputs; a second clean build was not performed.

Signal's old `Layout.getLineBottom(-1)` failure also reproduced on published
ATL 66c1d0fe. The bounded [text-layout milestone](TextLayoutContracts.md) fixes
that contract and UTF-16 line offsets, with tests on both architectures. No new
crash regression was observed in the listed final flows. This does not classify
untested functionality or the unresolved country selector as working.

## Follow-up checks

1. Physical Nura keyboard/input and the default file portal, without test helpers.
2. Signal country selection with an instrumented input trace and baseline comparison.
3. Immich hardware-rendered PC video, audio/cadence on Nura, and rapid scrolling.
4. A clean runtime build and installer/update flow using the documented source
   ART, core classes and optional APK verifier configuration.
