# Garnacha Boy — UX-first roadmap (fresh re-plan)

- Date: 2026-07-14
- Status: Approved (brainstorm 2026-07-14)
- Supersedes: `docs/superpowers/specs/2026-07-13-mgba-product-roadmap-design.md`
  (the original M0–M7 roadmap). That plan's completed work stands; this document
  re-derives the *remaining* direction with best-in-class UX as the organizing
  priority, rather than inheriting the old milestone shape.

## What this is

A fresh roadmap for the custom mGBA Android product (Garnacha Boy,
`com.trebuchetdynamics.garnacha`), re-planned from the current real state with
**best-in-class UX as the top priority**. Work is sequenced as impact-ordered
vertical slices, each of which ships a public build (Approach A).

## Decisions carried in (settled; not re-litigated)

| Decision | Value |
|---|---|
| Product | Custom mGBA app, GBA-first. Free, ad-free, no telemetry. |
| Distribution | GitHub Releases + F-Droid + Google Play. |
| Name / ID | Garnacha Boy / `com.trebuchetdynamics.garnacha` (permanent). |
| Core | Canonical mGBA 0.10.5, unmodified pinned submodule, MPL-2.0. |
| Top priority (this re-plan) | Best-in-class UX. |
| Sequencing | Approach A: impact-ordered UX vertical slices, ship each publicly. |

## Facts this plan is built on (proven, not assumed)

- **Playback is release-viable with no rendering rework.** The M1 physical-device
  gate measured a worst-case 2,469 µs against the 16,743 µs frame budget on a
  Snapdragon 8 Gen 3, 0 late frames in 90,896, no throttling. The Canvas +
  `AudioTrack` path stays; all effort goes to experience.
- **Already built and device-verified:** GBA emulation with 48 kHz audio,
  atomic private-file ROM import including transparent zip extraction, cartridge
  saves, single-sourced touch geometry (`ControlLayout`) with distinct portrait
  and landscape layouts, in-app MPL-2.0 notices, Garnacha Boy branding, and a
  signed-release CI workflow (fail-closed until signing is wired).
- **Open validation gates, not yet closed:** Bluetooth controllers untested,
  battery/thermal unmeasured, only one flagship device tested.

## Design foundation

`docs/design/ui-ux-study-2026-07-14.md` — the Pizza Boy teardown and the Garnacha
UI spec. The organizing UX insight: **the game is the product and the chrome
disappears**; every management surface is one gesture away, then gone. The
highest-value single feature is the in-game menu overlay.

## Guiding principles

- UX is the spine: phases are ordered by "feels premium" impact, and each ships
  a public build so real-use feedback sharpens the next slice.
- Borrow conventions and ergonomics, never proprietary assets or trade dress.
  Ship Garnacha's own flat, clean identity.
- One emulator core per app; mGBA stays an unmodified pinned submodule.
- No game or BIOS content is ever bundled.
- Every phase ends green: zero lint errors, tests passing, evidence recorded,
  and a device-verified build.

## Phases

### Phase 0 — Ship the working baseline → v0.1

Finish the near-complete release infrastructure so every later slice can ship
publicly. Wire `build.gradle` release signing to read the local
`keystore.properties`; set the four CI signing secrets; tag `v0.1.0`; verify a
clean install of the *downloaded* artifact imports a ROM and plays. This is the
enabler for continuous shipping.

**Gating dependency:** requires the release keystore and `keystore.properties`
to exist on the build machine. Nothing ships until this is resolved.

**Exit:** a signed v0.1.0 on GitHub Releases that a stranger can download and
play.

### Phase 1 — In-game menu overlay → v0.2 (centerpiece)

The single biggest "feels like a real emulator" upgrade. A translucent panel
that slides in **over the running game** (the game keeps rendering behind it),
opened by an edge-swipe or a small persistent handle. Contents, largest and
nearest to the thumb first:

- **Save state / Load state** with multiple labelled slots (this phase owns the
  save-state UI);
- **Fast-forward** toggle;
- **Reset**;
- **Settings** entry (screen itself lands in Phase 4);
- **Close**.

**Exit:** a player can save/load state to slots, fast-forward, and reset without
leaving the game; tag v0.2.

### Phase 2 — Disappear-the-chrome play feel → v0.3

The premium-feel pass, no new "features" — how it feels in the hand:

- Landscape controls become **semi-transparent gutter overlays that fade when
  idle** and re-assert on touch, so the game reads edge-to-edge.
- Portrait control polish; tunable control opacity; haptic feedback on presses.
- Rendering crispness: correct letterboxing and integer/near-integer scaling.

Builds on the single-sourced `ControlLayout`; no reintroduction of hand-computed
geometry.

**Exit:** landscape plays as an immersive, edge-to-edge experience with
fade-when-idle controls; tag v0.3.

### Phase 3 — ROM library → v0.4

Replace the single-ROM flow with a library: grid/list of imported ROMs, SAF
import, delete, last-played ordering, resume. No cover-art metadata yet
(post-1.0).

**Exit:** multi-ROM daily-driver usable; tag v0.4.

### Phase 4 — Settings + controllers → v0.5

A standard Android preference tree (far cheaper to build than a custom canvas,
instantly familiar), grouped:

- **Video:** scale, aspect, orientation lock. (Shaders/filters post-1.0.)
- **Audio:** enable, volume.
- **Controls:** touch layout size/opacity/position, **gamepad remapping**,
  haptics.
- **Emulation:** frameskip, fast-forward speed.

This phase closes the **Bluetooth controller** validation gate: test real
gamepads on the device and implement remapping against them.

**Exit:** the standard-emulator settings surface is complete and controllers are
tested and mappable; tag v0.5.

### Phase 5 — Public 1.0 launch → v1.0

- Final branding and icon; accessibility pass; onboarding/empty states.
- Privacy policy (no data collected); dependency/security audit.
- Play Console listing, F-Droid submission (reproducible build, fastlane
  metadata — started early, the queue is slow), store assets.
- **A beta round that closes the remaining validation gates:** controllers
  across multiple devices, battery/thermal over sustained sessions, and low-end
  / mid-range hardware where the frame budget could be tighter than the flagship
  M1 result.

**Exit:** v1.0 live on GitHub Releases and at least one store channel. Known
risk: Play review of emulators is unpredictable; GitHub/F-Droid hedge it, so a
Play rejection delays nothing else.

## Post-1.0 backlog (unordered)

Game Boy / Game Boy Color (same mGBA core — variable framebuffer, GB save types,
`.gb`/`.gbc` handling), rewind, cheats, shaders/filters, per-game profiles,
cover-art metadata, cloud save sync, link cable.

## Out of scope

- Any work on the frozen SkyEmu fork.
- Emulator cores other than mGBA (rules out multi-system).
- Monetization of any kind.
- Rendering/audio architecture rework — measured unnecessary by the M1 gate.

## Open risks

- **Phase 0 is blocked on the signing keystore existing on the build machine.**
  Every public release depends on it; it is the current critical-path blocker.
- **Trademark:** the "Garnacha Boy" name carries an accepted Game Boy trademark
  risk on the Play channel (ADR 0002). Cheap to reverse (name is a single string,
  independent of the app ID) up until the Play submission in Phase 5.
- **Single-device performance evidence:** the "no rendering rework" decision
  rests on one flagship. Phase 5's low-end beta is where that assumption is
  actually tested; if it fails there, a rendering/audio optimization slice
  re-enters the plan.
