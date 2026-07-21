# Pizza Boy responsive UI follow-up

Fresh live capture of Pizza Boy GBA 2.2.13 (`it.dbtecno.pizzaboygba`, version
code 232) on a Samsung SM-S928B. No ROM was opened. Every PNG has a matching
UI Automator XML hierarchy. Pizza Boy's orientation preference was restored to
Sensor after capture.

These files are interaction evidence, not assets to copy.

## Capture pairs

| Surface | Portrait | Landscape |
| --- | --- | --- |
| Play surface | [`portrait-home.png`](portrait-home.png) | [`landscape-home.png`](landscape-home.png) |
| In-game menu | [`portrait-menu.png`](portrait-menu.png) | [`landscape-menu.png`](landscape-menu.png) |
| Settings categories | [`portrait-settings-categories.png`](portrait-settings-categories.png) | [`landscape-settings-categories.png`](landscape-settings-categories.png) |
| Orientation choice | [`portrait-orientation-dialog.png`](portrait-orientation-dialog.png) | [`landscape-orientation-dialog.png`](landscape-orientation-dialog.png) |
| Controller mapping | [`portrait-controller-mapping-dialog.png`](portrait-controller-mapping-dialog.png) | [`landscape-controller-mapping-dialog.png`](landscape-controller-mapping-dialog.png) |
| Layout editor | [`portrait-layout-editor.png`](portrait-layout-editor.png) | [`landscape-layout-editor.png`](landscape-layout-editor.png) |
| Help dialog | [`portrait-help-dialog.png`](portrait-help-dialog.png) | [`landscape-help-dialog.png`](landscape-help-dialog.png) |

## New UI/UX findings

1. **Orientation changes geometry, not information architecture.** Category order,
   menu order, and dialog actions stay stable. Gameplay controls move into side
   gutters, while settings retain the same vertical reading order.
2. **Dialogs become wider and shallower in landscape.** The orientation choices
   occupy `[72,877]–[1008,1552]` in portrait and `[387,308]–[1818,983]` in
   landscape. The controller prompt uses the same centered responsive width.
3. **Controller mapping is a focused task.** The row summary shows the current
   binding; tapping it opens one prompt with a direct instruction plus `UNSET`
   and `OK`. Garnacha should retain its clearer accessibility labels while
   preserving this small interaction surface.
4. **Settings favor generous, consistent rows.** Category rows are about 151 px
   tall in both orientations. Landscape adds horizontal breathing room instead
   of shrinking row height or splitting the list into columns.
5. **The in-game drawer prioritizes reach over completeness.** Portrait exposes
   the full action list near the lower-left thumb. Landscape preserves row size
   and lets low-frequency actions fall below the fold rather than compressing
   every target.
6. **The fixed editor toolbar works across orientations.** Its six commands keep
   their order and move from equal 180 px portrait cells to equal 358 px
   landscape cells. The duplicated `Mute Button` accessibility labels remain a
   defect and should not be copied.
7. **The help modal is evidence of what to avoid.** It expands responsively, but
   remains a long wall of text. Garnacha's import empty state and short contextual
   guidance are preferable to a blocking tutorial.
8. **Visible disabled Pro rows aid discoverability but hurt scan quality.** Their
   very low contrast dominates several settings screens. Garnacha should disable
   unavailable actions clearly only when their presence helps the current task.

## Practical implications for Garnacha

- Keep the same action order across portrait and landscape; adapt placement and
  width, not terminology.
- Use centered, bounded dialogs for short choice or mapping tasks.
- Continue showing physical controller bindings inline.
- Preserve large touch targets and a fixed editor command order.
- Prefer concise contextual copy over tutorial dialogs.
- Do not imitate Pizza Boy branding, skins, iconography, Pro badges, or control
  artwork.
