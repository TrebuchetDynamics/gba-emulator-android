# Pizza Boy GBA live UI/UX reference

Captured from Pizza Boy GBA `2.2.13` (`it.dbtecno.pizzaboygba`, version code
232) on a Samsung SM-S928B at 1080×2340 / 450 dpi. No ROM was opened; the
captures contain only application chrome and controls. These are reference
evidence, not assets to copy.

Each PNG has a matching UI Automator XML hierarchy. Captures cover the play
surface, popup navigation, settings, and layout editor in both orientations.
The [`features/`](features/) walkthrough adds 26 screenshot/hierarchy pairs for
advanced actions, rewind, save-file tools, and every settings category. The
[Play Store listing study](play-store-listing-study.md) compares its advertised
Pro features with Garnacha's current Android path and prioritizes follow-up work.
A [2026-07-20 responsive follow-up](fresh-2026-07-20/README.md) adds paired
choice-dialog, controller-mapping, help, and refreshed core-surface evidence.
A [2026-07-21 deeper feature walkthrough](deeper-2026-07-21/README.md) adds
ROM-folder, control auto-hide, shader, controller-command, touch-tuning, and
BIOS evidence with a source-verified Garnacha gap ranking.

## Portrait

- [`portrait-home.png`](portrait-home.png): immersive play surface and controls.
- [`portrait-menu.png`](portrait-menu.png): lower-left in-game popup.
- [`portrait-settings-menu.png`](portrait-settings-menu.png): settings submenu.
- [`portrait-general-settings.png`](portrait-general-settings.png): settings categories.
- [`portrait-layout-editor.png`](portrait-layout-editor.png): direct-manipulation editor.

Measured from the hierarchy:

- Game surface: `[0,96]–[1080,816]`, a full-width 1080×720 3:2 region.
- Shoulder controls: `[54,1026]–[378,1215]` and `[702,1026]–[1026,1215]`.
- D-pad: `[0,1308]–[540,1848]`; A/B group: `[546,1449]–[1074,1707]`.
- Select/start: `[198,1975]–[522,2096]` and `[558,1975]–[882,2096]`.
- Menu/mute toolbar: bottom 144 px; controls remain clear of it.
- Main menu: 700 px wide, bottom-anchored, with 135 px rows.

## Landscape

- [`landscape-home.png`](landscape-home.png): maximized central play surface.
- [`landscape-menu.png`](landscape-menu.png): height-limited in-game popup.
- [`landscape-general-settings.png`](landscape-general-settings.png): responsive settings list.
- [`landscape-layout-editor.png`](landscape-layout-editor.png): landscape editor toolbar.

Measured from the hierarchy:

- Game surface: `[446,0]–[1894,965]`, centered at approximately 3:2.
- D-pad: `[96,414]–[564,882]`; A/B group: `[1753,508]–[2267,788]`.
- Shoulder controls occupy the upper side gutters; select/start move to the
  upper-right gutter.
- Menu/mute toolbar spans the bottom 144 px inside safe horizontal insets.
- The popup exposes seven rows at once; lower actions require scrolling.

## UX patterns worth retaining

1. The play screen is immersive: game and controls only.
2. Portrait stacks game above large thumb controls; landscape centers the game
   and moves controls into the side gutters.
3. A persistent lower-corner menu opens contextual actions without leaving play.
4. Layout editing happens in place. Six bottom actions provide accept, size up,
   size down, opacity down, opacity up, and reset.
5. Settings use a shallow category list: General, UI, Save, Joypad, Video, and
   Retro Achievements.

## Observed weaknesses

- Most virtual controls are image views without accessibility labels.
- In the layout editor, accept and reset are both exposed as `Mute Button` in
  the accessibility hierarchy.
- Dark settings icons have weak contrast against the dark gray background.
- The landscape popup hides lower-priority actions below the fold.
