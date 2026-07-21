# ADR 0003: Repository-wide Garnacha Boy identity

- Status: Accepted
- Date: 2026-07-18

## Decision

**Garnacha Boy** is the product identity on every current public surface in this repository: Android labels, desktop and web titles, native and libretro runtime names, package artifacts, CI artifacts, documentation entry points, and visual assets.

The primary Android application keeps its permanent ID, `com.trebuchetdynamics.garnacha`, as decided in [ADR 0002](0002-product-name.md). The legacy SkyEmu-derived Android client keeps `com.trebuchetdynamics.skyemu` so existing installs and private app data are not orphaned, but its label and generated APK name use Garnacha Boy.

The visual mark is an original geometric garnacha grape cluster with a control cross. It replaces the upstream seagull in generated app, desktop, and web icons without copying Nintendo, SkyEmu, or mGBA trade dress.

## Fork attribution and compatibility

A rebrand must not erase authorship. The repository therefore keeps:

- the SkyEmu MIT copyright and license in [`../../LICENSE`](../../LICENSE);
- explicit SkyEmu fork attribution in [`../../README.md`](../../README.md) and [`../../ACKNOWLEDGMENTS.md`](../../ACKNOWLEDGMENTS.md);
- the `upstream` Git remote and links to upstream issues and historical evidence;
- compatibility-sensitive `skyemu` identifiers where renaming would break settings, saves, caches, OAuth callbacks, JNI bindings, or established libretro firmware paths;
- the `SkyEmu` name for the upstream color-correction algorithm and historical accuracy comparisons.

Those retained identifiers describe provenance, compatibility, or historical facts; they are not the current product name.

## Consequences

- Cross-platform CMake outputs use `GarnachaBoy`; libretro outputs use `garnachaboy_libretro`.
- Existing scripts or integrations that consumed old `SkyEmu` artifact names must update.
- Existing user data remains discoverable through retained compatibility paths.
- Future product-facing strings and assets must use Garnacha Boy, while upstream credit remains visible and intact.
