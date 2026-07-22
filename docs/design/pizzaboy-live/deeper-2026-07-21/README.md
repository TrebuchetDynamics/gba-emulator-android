# Pizza Boy deeper feature walkthrough

Captured from Pizza Boy GBA Basic 2.2.13 (`it.dbtecno.pizzaboygba`, version
code 232) on a Samsung SM-S928B on 2026-07-21. No ROM, file picker,
credential field, external link, or private storage was opened. Every PNG has a
matching UI Automator XML hierarchy. Pizza Boy's orientation was restored to
**Sensor**, and Android auto-rotation was restored after capture.

These files are interaction evidence, not assets to copy.

## New capture pairs

| Surface | Portrait | Landscape |
| --- | --- | --- |
| Empty play surface | [`portrait-home.png`](portrait-home.png) | [`landscape-home.png`](landscape-home.png) |
| Main menu | [`portrait-menu.png`](portrait-menu.png) | [`landscape-menu.png`](landscape-menu.png) |
| Settings submenu | [`portrait-settings-submenu.png`](portrait-settings-submenu.png) | — |
| ROM-folder empty state | [`portrait-rom-folders-empty.png`](portrait-rom-folders-empty.png) | [`landscape-rom-folders-empty.png`](landscape-rom-folders-empty.png) |
| UI auto-hide timing | [`portrait-ui-hide-timing-dialog.png`](portrait-ui-hide-timing-dialog.png) | [`landscape-ui-hide-timing-dialog.png`](landscape-ui-hide-timing-dialog.png) |
| UI auto-hide targets | [`portrait-ui-hide-targets-dialog.png`](portrait-ui-hide-targets-dialog.png) | [`landscape-ui-hide-targets-dialog.png`](landscape-ui-hide-targets-dialog.png) |
| GPU shader choices | [`portrait-video-gpu-shader-dialog.png`](portrait-video-gpu-shader-dialog.png) | [`landscape-video-gpu-shader-dialog.png`](landscape-video-gpu-shader-dialog.png) |
| Extra controller bindings | [`portrait-joypad-extra-bindings.png`](portrait-joypad-extra-bindings.png) | [`landscape-joypad-extra-bindings.png`](landscape-joypad-extra-bindings.png) |
| Touch/input options | [`portrait-general-control-options.png`](portrait-general-control-options.png) | [`landscape-general-control-options.png`](landscape-general-control-options.png) |
| Runtime options | [`portrait-general-runtime-options.png`](portrait-general-runtime-options.png) | [`landscape-general-runtime-options.png`](landscape-general-runtime-options.png) |
| BIOS options | included in runtime capture | [`landscape-general-bios-options.png`](landscape-general-bios-options.png) |

## Newly observed behavior

1. **ROM folders are a first-class import path.** The empty manager has one
   large `ADD ROMS FOLDER` action. It does not mix folder setup with the normal
   play menu.
2. **Touch chrome can hide independently of gameplay.** Timing choices are
   Never, ten seconds without touches, or when an external pad connects. A
   second choice hides either skin plus buttons or buttons only.
3. **Controller mappings include emulator commands.** In addition to GBA keys,
   Pizza Boy maps Menu, Turbo A, Turbo B, Speed +, and Speed -.
4. **Touch behavior is highly tunable.** Options include bounded/unbounded
   D-pad behavior, dead zone, disabled diagonals, A+B between-button input,
   vibration intensity/length, and double-tap screenshot.
5. **Video effects are a short curated list.** GPU choices are Default,
   Grayscale, HQ2X, HQ4X, LCD3X, and Scanlines rather than a shader browser.
6. **Custom BIOS is explicit and removable.** The settings separate loading a
   BIOS file, using it at boot, the selected file, and BIOS animation.
7. **Portrait and landscape preserve terminology and ordering.** Dialogs widen
   in landscape; rows keep their height and scroll rather than compressing.

## Verified Garnacha gaps

The comparisons below were checked against the current Android source, not
inferred only from screenshots.

| Pizza Boy capability | Garnacha today | Recommendation |
| --- | --- | --- |
| Import/manage ROM folders | Single-document `.gba`, `.gb`, `.gbc`, and `.zip` import into the private library; no `ACTION_OPEN_DOCUMENT_TREE` path | **P1:** add bounded SAF folder batch import only if multi-file setup is a repeated need. Do not add background folder watching first. |
| Export/import battery saves | Local automatic battery-save persistence only; no create-document or restore flow | **P0:** highest-value offline gap. Design per-game `.sav` export/import with overwrite confirmation and atomic replacement. |
| Bind controller Menu/Turbo/Speed actions | Controller map emits only GBA key bits; speed and menu remain touch drawer actions | **P1:** add Menu and fast-forward command bindings after the pending physical controller walkthrough. Keep hardware macros out of scope. |
| D-pad dead-zone/diagonal tuning | Fixed touch geometry with multi-touch and custom layout; no dead-zone or diagonal switches | **P2:** add only if device testing finds accidental diagonals. Touch macros already cover deliberate A+B combinations. |
| Haptic intensity and duration | One accessible haptics on/off preference | **P2:** current boolean is sufficient until hardware variance produces complaints. |
| Curated GPU shaders | Crisp integer/fill scaling and native bilinear smoothing through Canvas | **Deferred:** requires a GPU rendering surface and the existing performance/equivalence gates. |
| Custom BIOS | Android mGBA bridge has no safe user-BIOS loading API | **Deferred:** do not expose a picker until platform, size, lifecycle, and licensing checks exist. |
| RetroAchievements, Drive, multiplayer | Offline-only; no account/network layer | **Deferred:** separate privacy, authentication, conflict, and support projects. |
| Skins/backgrounds | Garnacha identity plus configurable control geometry/opacity/macros | **Skip:** layout flexibility is present; copied skins or trade dress are not needed. |

## Already covered by Garnacha

- Separate portrait/landscape control layouts and up to eight touch macros.
- A Pizza Boy-inspired touch-visibility choice: always visible, hide after ten
  seconds, or hide with a connected gamepad. Idle controls return on touch
  without sending an accidental game input; menu and mute remain available.
- Haptics, controller remapping, and accessible dialogs.
- Integer/fill scaling, optional smooth filtering, slow motion, fast-forward,
  screenshot capture, rewind, manual slots, and rotating recovery saves.
- A responsive offline library and a primary in-game **Edit layout** action.

## Recommended next slice

Design battery-save export/import first. It is offline, protects user-owned
progress, uses Android's native document picker, and has a clear atomic test
boundary. Folder batch import and controller command bindings should remain
separate follow-ups; they should not be bundled with save portability.
