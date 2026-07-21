# Pizza Boy GBA extended feature walkthrough

Live captures from Pizza Boy GBA 2.2.13 on SM-S928B. Every PNG has a matching
UI Automator XML hierarchy. No ROM, account value, password, or personal storage
path was captured. These are interaction references, not assets to copy.

## In-game feature overlays

| Feature | Portrait | Landscape |
| --- | --- | --- |
| Advanced menu | [`portrait-advanced-menu.png`](portrait-advanced-menu.png) | [`landscape-advanced-menu.png`](landscape-advanced-menu.png) |
| Save-file tools | [`portrait-save-files-menu.png`](portrait-save-files-menu.png) | [`landscape-save-files-menu.png`](landscape-save-files-menu.png) |
| Rewind choices | [`portrait-rewind-menu.png`](portrait-rewind-menu.png) | [`landscape-rewind-menu.png`](landscape-rewind-menu.png) |

Observed features:

- Save-file import from ZIP or folder, export to ZIP, and deletion.
- Backup `.sav` restore and cache maintenance.
- Optional LCD mode, cloud sync, rewind intervals, and other Pro-gated actions.
- Log export, community link, and visible app version.
- Disabled premium actions remain visible but clearly dimmed and tagged `PRO`.

## Settings navigation

- [`landscape-settings-submenu.png`](landscape-settings-submenu.png): compact
  in-game route to ROM folders or general settings.
- [`landscape-settings-categories.png`](landscape-settings-categories.png):
  shallow category list for General, UI, Save, Joypad, Video, and Retro
  Achievements.

## General

| View | Portrait | Landscape |
| --- | --- | --- |
| Top | [`portrait-settings-general-top.png`](portrait-settings-general-top.png) | [`landscape-settings-general-top.png`](landscape-settings-general-top.png) |
| Scrolled | [`portrait-settings-general-bottom.png`](portrait-settings-general-bottom.png) | [`landscape-settings-general-bottom.png`](landscape-settings-general-bottom.png) |

Startup ROM behavior, vibration and rumble, screenshot gesture, D-pad dead zone
and diagonals, combined A+B input, speed-bar behavior, orientation, and BIOS
loading.

## UI

| View | Portrait | Landscape |
| --- | --- | --- |
| Top | [`portrait-settings-ui-top.png`](portrait-settings-ui-top.png) | [`landscape-settings-ui-top.png`](landscape-settings-ui-top.png) |
| Scrolled | [`portrait-settings-ui-bottom.png`](portrait-settings-ui-bottom.png) | [`landscape-settings-ui-bottom.png`](landscape-settings-ui-bottom.png) |

Aspect crop, speed bar, quick-save controls, movable toolbar controls, C/turbo
buttons, cutout and landscape fullscreen handling, hide behavior, toolbar,
box art, skins, themes, and separate portrait/landscape backgrounds.

## Save states

- [`portrait-settings-save-top.png`](portrait-settings-save-top.png)
- [`landscape-settings-save-top.png`](landscape-settings-save-top.png)

Save folder, quick-load/save confirmation, automatic save/load states, rotating
slots, timed autosave, and explicit quick-save slots.

## Joypad mapping

| View | Portrait | Landscape |
| --- | --- | --- |
| Top | [`portrait-settings-joypad-top.png`](portrait-settings-joypad-top.png) | [`landscape-settings-joypad-top.png`](landscape-settings-joypad-top.png) |
| Scrolled | [`portrait-settings-joypad-bottom.png`](portrait-settings-joypad-bottom.png) | [`landscape-settings-joypad-bottom.png`](landscape-settings-joypad-bottom.png) |

Explicit mappings for directions, A/B/A+B, shoulders, Start/Select, turbo,
speed controls, and menu actions. Each row shows the current physical binding.

## Video

- [`portrait-settings-video-top.png`](portrait-settings-video-top.png)
- [`landscape-settings-video-top.png`](landscape-settings-video-top.png)

Linear filtering, LCD blur, perfect-pixel scaling, and GPU/CPU shader choices.

## Retro Achievements

- [`portrait-settings-retro-achievements-top.png`](portrait-settings-retro-achievements-top.png)
- [`landscape-settings-retro-achievements-top.png`](landscape-settings-retro-achievements-top.png)

Enable/login flow, Hardcore, Encore, unofficial achievements, spectator mode,
progress popups, event bar, and popup placement. Credential fields were empty in
these captures.

## Reusable UX lessons

1. Keep active play visually quiet; expose depth through a thumb-reachable menu.
2. Use shallow categories instead of one enormous settings screen.
3. Show current mappings and values inline, not only inside dialogs.
4. Separate free and unavailable features visually without hiding discoverability.
5. Give save-file import/export and controller mapping first-class surfaces.
6. Preserve orientation-specific layouts while keeping the information model the same.
