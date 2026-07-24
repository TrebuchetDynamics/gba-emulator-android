# ADR 0001: mGBA-only product core

- Status: Accepted
- Date: 2026-07-21
- Supersedes: the former dual-core plan
- mGBA pin: `26b7884bc25a5933960f3cdcd98bac1ae14d42e2` (`0.10.5`)

## Decision

Garnacha Boy has one supported emulator core: canonical mGBA under MPL-2.0. The Android application and reusable core library live under `mgba-android/`; mGBA remains an unmodified `vendor/mgba` submodule.

Former secondary emulator clients and their desktop, web, libretro, and legacy Android build paths are removed. The repository no longer builds or packages a second emulator core.

## Why

A single mGBA path avoids duplicate behavior, APK/build confusion, unnecessary maintenance, and incompatible save/settings surfaces. It also matches the product's actual Android implementation and release target.

## License boundary

- Keep canonical mGBA in the `vendor/mgba` submodule.
- Package mGBA's MPL-2.0 license in the AAR and app notices.
- Publish source for any future modifications to MPL-covered mGBA files and retain third-party notices.

## Validation gates

- The APK and AAR build for `arm64-v8a` and `x86_64` with no lint errors.
- JNI version/lifecycle, MIT ROM execution, frame/audio output, save states, savedata, and closed-session behavior pass Android instrumentation.
- The API 34 x86_64 app loads and renders an MIT GBA test ROM, maps touch controls, and survives pause/resume without fatal logs.
- Physical arm64 performance, audio, controller, save, battery, and thermal tests remain release gates.
