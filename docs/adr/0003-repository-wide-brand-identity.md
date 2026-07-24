# ADR 0003: Repository-wide Garnacha Boy identity

- Status: Accepted
- Date: 2026-07-18

## Decision

**Garnacha Boy** is the product identity on every current public surface in this repository: Android labels, package artifacts, CI artifacts, documentation entry points, and visual assets.

The Android application keeps its permanent ID, `com.trebuchetdynamics.garnacha`, as decided in [ADR 0002](0002-product-name.md). The product has one supported Android client and one emulator core: mGBA.

The visual mark is an original geometric garnacha grape cluster with a control cross. It does not copy Nintendo or mGBA trade dress.

## Licensing

The repository's primary emulator dependency is canonical mGBA under MPL-2.0. Its source remains pinned and unmodified under `vendor/mgba`, and its license is included in the app and AAR notices. See [`../../ACKNOWLEDGMENTS.md`](../../ACKNOWLEDGMENTS.md).

## Consequences

- Android builds and release artifacts are the only supported product outputs.
- The former cross-platform and legacy Android build paths are not retained for compatibility.
- Future product-facing strings and assets use Garnacha Boy, while upstream mGBA credit remains visible.
