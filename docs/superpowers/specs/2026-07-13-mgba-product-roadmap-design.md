# mGBA product roadmap — design spec

- Date: 2026-07-13
- Status: Approved (brainstorm 2026-07-13)
- Scope: Roadmap for the custom mGBA Android product (`mgba-android/`) from its current audited state to a public 1.0 on Google Play, F-Droid, and GitHub Releases, plus the first post-launch release.

## Decisions made during brainstorming

| Question | Decision |
|---|---|
| Track focus | Custom mGBA product only. The SkyEmu fork is a completed stopgap: frozen, kept buildable, no further work. |
| Distribution | Both Google Play and open channels (GitHub Releases, F-Droid). |
| Test hardware | A physical arm64 Android device is available now. |
| 1.0 feature set | Standard emulator 1.0: ROM library, settings, fast-forward, touch/gamepad remapping, screen scaling options, multiple save-state slots. Rewind/cheats/shaders deferred. |
| Consoles | GBA-only 1.0; Game Boy / Game Boy Color in 1.1 (same mGBA core, no new emulator core ever added to this app). |
| Roadmap shape | A+C hybrid: validate on physical hardware first, then ship a public GitHub pre-release tag at every milestone until 1.0. |
| Pace | Milestone-gated, not calendar-gated. |

## Positioning

The free, open-source, ad-free, maintained GBA emulator. Pizza Boy GBA is closed-source and was withdrawn from Play in late 2023; the remaining free Play options are ad-heavy wrappers. Lemuroid owns "multi-system"; this product differentiates on single-purpose focus and polish, not feature count.

## Guiding principles

- One emulator core per app (ADR 0001 stands). mGBA remains an unmodified, pinned `vendor/mgba` submodule; its MPL-2.0 license ships in every artifact.
- No bundled game or BIOS content, ever. Only MIT-licensed test ROMs in tests; validation with other content uses only the developer's own authorized copies.
- Every milestone ends green: zero lint errors, instrumentation suite passing, evidence recorded as a receipt in `docs/validation/`.
- No repository-stored release keys; the release keystore is generated and held locally.
- From M3 onward, every milestone ships a signed public GitHub pre-release tag.

## Milestones

### M0 — Baseline

Land the uncommitted work on `mvp` (JNI layer, `MgbaSession`, `MainActivity`/`EmulationRunner`, instrumentation tests, docs) and get CI green. Freeze the SkyEmu track.

**Exit criteria:** clean worktree; CI green on both build paths.

### M1 — Physical-device validation (decision gate)

Run the existing benchmark APK through the documented release gates on the physical arm64 device:

- sustained gameplay sessions (30–60 min) with frame-pacing measurements;
- audio latency and underrun counts;
- Bluetooth controller input;
- pause/resume and process-death recovery;
- save-state and cartridge-save integrity;
- battery drain and thermal throttling over a session.

Test content: MIT `gba-tests` ROMs plus demanding homebrew; other content only from authorized personal copies.

**Exit criteria:** a written `docs/validation/` receipt with a verdict — either "the current Canvas + AudioTrack path is release-viable" or a concrete defect list that defines M2's scope. This milestone is a decision point, not a checkbox.

### M2 — Rendering and audio hardening

Scope is set entirely by M1's verdict. Expected candidates: Canvas → `SurfaceView`/OpenGL rendering with Choreographer-driven frame pacing; audio buffer tuning or a low-latency AAudio path. If M1 passes outright, this milestone collapses to nothing.

*Early signal (2026-07-13/14, Galaxy S24 Ultra, commercial title):* frame work
measures 2.2–3.4 ms against the 16.743 ms budget — roughly 5–7× headroom — with
full-speed 598-frame windows, no late frames, and no audio underruns after
startup warm-up. On this hardware the existing Canvas + `AudioTrack` path looks
release-viable, and this milestone may collapse to nothing. Not yet the verdict:
the sustained session under thermal load is the gate that decides it.

**Exit criteria:** M1's measurement suite re-run on the physical device, passing with headroom.

### M3 — Release plumbing and v0.1 (first public tag)

- Product name and placeholder icon.
- Locally held release keystore; signing wired into the build without committing secrets.
- Versioning scheme and changelog convention.
- CI job that builds, signs, and attaches release APKs to GitHub Releases on tag push.
- In-app licenses/notices screen (mGBA MPL-2.0, test-ROM attributions).

**Exit criteria:** tag v0.1; a stranger can download a signed APK from GitHub Releases and play a GBA ROM.

### M4 — ROM library (v0.2)

Library screen replacing the single-ROM flow: list of imported ROMs, SAF import, delete, last-played ordering, resume. No cover-art metadata (post-1.0 backlog).

*Already delivered early (2026-07-14):* transparent zip import (`RomArchive`,
commits `005b53a8`/`ec884e83`) — device testing showed users naturally pick the
archive a ROM ships in, and the core rejected it.

**Exit criteria:** multi-ROM daily-driver usable; tag v0.2.

### M5 — Settings and input (v0.3)

Settings screen; touch-layout adjustment; gamepad remapping; screen scaling and orientation options; fast-forward; multiple save-state slots with UI; auto-save-state on exit. Completes the standard-emulator 1.0 feature set.

*Already delivered early (2026-07-13):* a correct default landscape touch
layout (`ControlLayout`, commit `9ce30ab0`). This was not cosmetic — controls
were sized off the screen's long edge, and drawing and hit-testing computed
conflicting geometry, so touch targets did not match the drawn buttons. The
geometry is now single-sourced and unit-tested, which is also the foundation the
remaining M5 layout-adjustment work builds on.

**Exit criteria:** 1.0 feature-complete; tag v0.3.

### M6 — 1.0 launch (Play + F-Droid + GitHub)

- Final branding and icon; onboarding and empty states; accessibility pass.
- Dependency/security audit.
- Privacy policy (no data collected).
- Play Console listing, store assets, internal-testing beta round with real users.
- F-Droid submission (reproducible build, fastlane metadata) — started early in the milestone because the inclusion queue is slow.

**Exit criteria:** v1.0 live on GitHub Releases and at least one store channel. Known risk: Play review of emulators is unpredictable; GitHub/F-Droid hedge it, so a Play rejection delays nothing else.

### M7 — v1.1: Game Boy / Game Boy Color

Enable mGBA's GB core in the existing build (still the same core codebase — no new emulator core); handle the 160×144 framebuffer, GB save types, and `.gb`/`.gbc` in import and library; instrumentation tests with MIT GB test ROMs.

**Exit criteria:** tag v1.1.

## Post-1.1 backlog (unordered)

Rewind, cheats, shaders/filters, per-game profiles, cover-art metadata, cloud save sync, fast-forward speed control, link cable.

## Out of scope

- Any work on the SkyEmu fork beyond keeping it buildable.
- Emulator cores other than mGBA (rules out NES/SNES/NDS and any multi-system direction).
- Monetization of any kind: the app is free and ad-free.
