# ADR 0001: Separate the SkyEmu MVP from the custom mGBA product core

- Status: Accepted
- Date: 2026-07-09
- Upstream pins: SkyEmu `01516d6798e3652b583e6a366085bb51c43b528d`; mGBA `26b7884bc25a5933960f3cdcd98bac1ae14d42e2` (`0.10.5`)

## Decision

Maintain two isolated tracks:

1. The installable MVP remains a thin fork of SkyEmu under its MIT license.
2. The custom product is an Android app and reusable core library around canonical mGBA under MPL-2.0.

The SkyEmu MVP must not package both emulator cores. The mGBA implementation stays isolated in `mgba-android/`; promotion to a release build still requires physical-device validation.

## Why

SkyEmu already supplies a working Android frontend and is the shortest path to a permissively licensed APK. mGBA offers a mature, separable GBA core and a better boundary for a custom UI, but requires a product-owned Android host. Mixing both immediately would increase APK size, duplicate behavior, and make debugging and licensing less clear.

## License boundary

- Preserve the SkyEmu MIT notice in `LICENSE`.
- Keep canonical mGBA in the `vendor/mgba` submodule instead of copying or modifying its source.
- Package mGBA's MPL-2.0 license in the AAR.
- Publish source for any future modifications to MPL-covered mGBA files and retain third-party notices.

## Validation gates

SkyEmu MVP:

- Android lint has no errors.
- A debug APK builds for API 35.
- ROM import uses the Storage Access Framework and private app storage.
- No shared release signing key is stored in the repository.
- API 34 x86_64 emulator launch, DocumentsUI import, and MIT test-ROM rendering pass; physical arm64 gameplay remains required.

mGBA custom product:

- The APK and AAR build for `arm64-v8a` and `x86_64` with no lint errors.
- JNI version/lifecycle, MIT ROM execution, frame/audio output, save states, savedata, and closed-session behavior pass Android instrumentation.
- The API 34 x86_64 app loads and renders an MIT GBA test ROM, maps touch controls, and survives pause/resume without fatal logs.
- Physical arm64 performance, audio, controller, save, battery, and thermal tests remain release gates.
