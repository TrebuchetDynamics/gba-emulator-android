# Pizza Boy A Pro Play Store feature study

Reviewed 2026-07-18 from:

- [Pizza Boy A Pro listing](https://play.google.com/store/apps/details?id=it.dbtecno.pizzaboygbapro&hl=en&gl=US)
- [Pizza Boy A Pro data safety](https://play.google.com/store/apps/datasafety?id=it.dbtecno.pizzaboygbapro&hl=en&gl=US)
- [Pizza Boy A Basic listing](https://play.google.com/store/apps/details?id=it.dbtecno.pizzaboygba&hl=en&gl=US)
- [Pizza Emulators website](https://pizzaemulators.com)

This is the paid **Pizza Boy A Pro** listing, not the Basic build used for the
live device captures. Treat listing text as competitor marketing claims, not
independent proof of accuracy, performance, battery life, or privacy behavior.
Store metadata and selected reviews change over time.

## Store positioning

At review time the US listing showed a 4.8 rating, about 18.4K reviews, 100K+
downloads, a $5.99 price, and a #5 top-paid Arcade rank. Its carousel contains
12 portrait marketing images centered on three promises: **Simple**,
**Flexible**, and **Customizable**.

The screenshots mostly demonstrate:

- portrait and landscape play;
- multiple color skins and console-like frames;
- controls overlaid beside or below the game;
- visibly different control positions and screen proportions;
- a minimal toolbar during play.

This reinforces the live-device finding: layout flexibility is the product
benefit. Garnacha should keep its own visual identity and direct layout editor,
not copy Pizza Boy's skins, console shell, artwork, colors, or marketing assets.

## Full advertised feature inventory

### Pro additions

- Customizable skins and enhanced GUI.
- Cheats.
- Google Drive sync.
- Quick and automatic save.
- Local and Wi-Fi multiplayer/link play.
- Enhanced settings and controls.
- Custom BIOS.
- Gyroscope, light sensor, tilt sensor, and rumble-pack cartridge hardware.
- Rewind.

### Shared/basic capabilities

- Save and restore states.
- Slow motion and fast-forward.
- Full button size/position customization.
- Hardware controller support.
- Shaders.
- ZIP and 7z archive loading.
- RetroAchievements.
- Native-performance, accuracy, battery, and 60 FPS claims.

The Basic listing additionally advertises **JPEG screenshot capture**. A current
review praises the persistent speed indicator, controller-attachment support,
wireless link play, and **Rotate Save States**. Those are useful product clues,
not formal specifications.

## Release and review lessons

The listing's What's New section showed versions 3.7.7–3.7.10 with an emulation
opcode fix, repeated ZTE crash fixes, and a quick hardware button for AI
translation. Selected reviews reveal more important quality lessons:

- A visible fast/slow-speed indicator makes speed controls understandable.
- Rotating save states are valued because they preserve recovery history.
- Users expect paid emulators to work fully offline; one review reports online
  entitlement verification blocking travel use.
- Rewind is only valuable when crash-safe; one review reports a rewind crash.
- Skins need a real off state, especially with external controllers.
- External-controller and wireless-link support are differentiators, but only
  after basic lifecycle reliability.

Garnacha is already local-first and has no entitlement check. Preserve that.
Do not add AI translation without a concrete user need; it adds network,
privacy, UI, and hardware-binding complexity unrelated to emulation quality.

## Data-safety lesson

The developer declares **no data collected** and **no data shared**, despite
advertising Drive sync and RetroAchievements. Garnacha must not assume network
features are privacy-neutral: any future account, cloud, telemetry, or
achievement integration requires a fresh data-flow audit and accurate Play
Data Safety disclosure.

## Claims and Garnacha gaps

| Listing claim | Garnacha Boy today | Decision |
| --- | --- | --- |
| Beautiful GUI / enhanced settings | Dark, game-first UI; grouped settings cards | Keep polishing accessibility and states; do not copy Pizza Boy styling. |
| Customizable skins | Per-orientation control position, scale, and opacity editor | Skip full skins. Add layout presets only if repeated setup is observed. |
| Cheats | Not exposed by the Android mGBA bridge | Later: add only after a safe per-game code model and validation exist. |
| Google Drive sync | Local private ROM, battery-save, and state storage only | Defer. OAuth, conflicts, privacy disclosure, and recovery semantics make this a separate project. |
| Quick/Auto save | Four stable manual slots plus three rotating lifecycle autosaves with automatic resume and a user-selectable recovery-history page | Present; recovery generations show relative age and remain separate from manual slots. |
| Rotate Save States | Three dedicated autosave generations | Present and kept separate from manual slots. |
| Local/Wi-Fi multiplayer | Not exposed | Defer until single-player lifecycle, saves, and performance are release-grade. |
| Improved controls | Large touch controls, idle fade, haptics, hardware remapping, direct portrait/landscape editing with selected-control size/opacity feedback, and controller-only mode | Strong coverage. Add controller identity/conflict feedback only when needed. |
| Custom BIOS | No user BIOS picker or JNI bridge | High-value compatibility feature after autosave. Validate platform and expected file size without bundling BIOS content. |
| Gyroscope/light/tilt/rumble cartridge hardware | Android host does not bridge device peripherals into mGBA | Add one peripheral at a time, backed by game-specific tests. |
| Rewind | Five-second rewind backed by a 24 MiB memory-capped state timeline | Present; extend duration only after longer thermal/performance testing. |
| Accuracy / low battery / 60 FPS | Native mGBA core with frame metrics and benchmark tooling; no broad device claim | Measure sustained frame time, audio underruns, thermals, and battery before advertising. |
| Save and restore states | Four atomic per-ROM slots | Present. Polish with age/previews and clearer overwrite feedback. |
| Slow motion / fast-forward | Mutually exclusive ½× slow motion and configurable 2×–4× fast-forward with temporary in-play status; altered-speed audio is paused to avoid stutter | Present through the thumb menu without adding permanent play chrome. |
| Button customization | Direct layout editor plus up to eight arbitrary touch-combination buttons per orientation, each optionally using fixed 15 Hz whole-combination Turbo | Present. Touch macros have no recorded sequences or adjustable rate; keep the mode skin-free and controller-friendly. |
| Hardware joypads | Remappable physical-controller bindings and optional hidden touch controls | Present. Preserve full play without touch chrome. |
| Shaders | Crisp integer/fill scaling plus optional native bilinear smoothing through Android Canvas; no shader pipeline | Useful linear-filtering subset present; defer actual shaders until rendering moves to a GPU surface. |
| ZIP and 7z archives | Safe streaming ZIP import with decompressed-size cap plus signature-based 7z guidance; no 7z extraction | Advertise ZIP now. Explain how to extract 7z files; add extraction only with a maintained audited dependency and demand. |
| Screenshot capture | Saves the clean emulated frame to Android Pictures without control chrome | Present through scoped MediaStore storage. |
| RetroAchievements | Not present | Later account/network feature requiring authentication, privacy, offline, and hardcore-mode design. |

## Implemented from this study

- Three atomic rotating autosaves, automatic lifecycle resume, a relative-age recovery-history page, and unchanged manual slots.
- Mutually exclusive ½× slow motion and 2×–4× fast-forward with a transient speed/audio-status badge that leaves normal play chrome-free.
- Clean-frame PNG capture through scoped Android Pictures storage.
- Optional native smooth-pixel filtering while preserving crisp pixels as the default.
- Accessible import-error dialogs with actionable ZIP, multi-ROM, invalid-ROM, size-limit, and unsupported-7z guidance.
- Optional controller-only mode that hides touch controls when a gamepad is present.
- A contextual layout editor with per-orientation arbitrary touch-combination buttons, fixed whole-combination Turbo, direct position/size editing, and an eight-button limit; no sequence recording or adjustable Turbo rate.
- An offline-only APK with no Internet permission.
- Five-second rewind with a 24 MiB cap, future-branch discard, unit coverage, and a connected-device no-crash walkthrough.

## Recommended sequence

### P0 — polish the existing product

1. **Save-state clarity**
   - Show each occupied slot's age and confirm before overwrite.
   - Add preview images only if state and image commits remain bounded and atomic.
2. **Import clarity**
   - Advertise `.gba`, `.gb`, `.gbc`, and `.zip` in import copy.
   - Report multiple-ROM archives and unsupported 7z files directly. **Implemented.**
3. **Performance gates**
   - Turn existing frame/audio metrics into a repeatable 30-minute device check.
   - Soak rewind and autosave across low-memory and thermal-pressure conditions.

### P1 — bounded offline features

1. Optional rewind-duration choices after the five-second path is proven stable.
2. Custom BIOS selection and removal.
3. One cartridge peripheral at a time, starting only after mGBA API feasibility is proven.

### P2 — power-user additions

1. Cheats with per-game enable/disable and invalid-code errors.
2. GPU renderer and a small curated shader set.
3. 7z import if ZIP usage shows real archive demand.

### P3 — network/product infrastructure

RetroAchievements, cloud sync, and multiplayer should remain separate projects.
Each adds account, privacy, offline, conflict, and support obligations larger
than a settings row.

## Differentiation to preserve

- Garnacha's current Android path supports GBA plus GB/GBC; the Pizza Boy A Pro
  listing explicitly limits itself to GBA.
- Garnacha works locally without ads, upsells, entitlement checks, or setup walls.
- Prefer reliable user-owned ROMs and saves over feature-count parity.
