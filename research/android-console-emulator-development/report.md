# Building an Android Game Boy Advance emulator app

Research date: 2026-07-09
Depth: Comprehensive
Scope: academic literature, official Android guidance, GBA technical references, and open-source projects. No repositories were cloned, built, or integrated.

## Bottom line

Do **not** write a GBA emulator core from scratch unless hardware research is the product. The shortest credible routes are:

1. **Permissive MVP:** fork [SkyEmu](https://github.com/skylersaleh/SkyEmu) (MIT), which already has an Android project and Android CI.
2. **Custom Android product:** build a small Kotlin Android shell around the canonical [mGBA](https://github.com/mgba-emu/mgba) core (MPL-2.0) through the NDK/JNI. This gives better product control but requires a new Android integration layer.
3. **GPL open-source app:** fork [Lemuroid](https://github.com/Swordfish90/Lemuroid), narrow it to GBA, and retain its mGBA/libretro stack. This is the most complete Android reference, but Lemuroid and LibretroDroid are GPL-3.0.

The unresolved first decision is licensing: GPL-compatible open source versus a closed/commercial product. That choice changes the architecture more than any performance concern.

## What a GBA emulator must reproduce

GBATEK describes the GBA as an ARM7TDMI system running ARM and Thumb code at 16.78 MHz, with a 240×160 display and a 280,896-cycle frame of about 59.737 Hz. A core must model more than CPU instructions:

- ARM/Thumb CPU state, pipeline, exceptions, and instruction timing;
- memory map, wait states, cartridge prefetch, and open-bus behavior;
- interrupts, four timers, and DMA;
- scanline/pixel PPU behavior, backgrounds, sprites, blending, windows, HBlank, and VBlank;
- legacy sound channels plus direct-sound FIFOs;
- SRAM/Flash/EEPROM saves, RTC, solar/tilt/rumble peripherals, and BIOS behavior;
- deterministic save states and synchronization between CPU, PPU, audio, DMA, and timers.

This breadth explains why an established core is a much safer product dependency than a fresh implementation.

Source: [GBATEK – GBA/NDS Technical Info](https://problemkaputt.de/gbatek.htm), retrieved copy at `platform-sources/gbatek.html`.

## Recommended architecture

```text
Kotlin UI / Activity
├── ROM picker + library metadata       Android Storage Access Framework
├── touch overlay + controller mapping  KeyEvent / MotionEvent
├── lifecycle + settings                pause, autosave, resume
└── Native session bridge               JNI, one owning emulation thread
    ├── GBA core                         SkyEmu or mGBA
    ├── video                            240×160 frame → GLES texture → Surface
    ├── audio                            core samples → ring buffer → Oboe callback
    └── persistence                      battery save + versioned save states
```

### Runtime boundaries

- Let one native emulation thread own all mutable core state.
- Send input and lifecycle commands through a small queue; do not call the core concurrently from UI and audio threads.
- Present the 240×160 output as a texture on a `SurfaceView`; scale with integer/aspect-preserving modes before adding shaders.
- Use audio buffer fill as the primary throttle signal and present the latest completed frame on display vsync. Do not change emulated speed from 59.737 Hz to match a nominal 60/90/120 Hz screen.
- Keep rewind, netplay, cheats, archive loading, shader packs, and cloud sync out of the first build.

Android's [GameActivity](https://developer.android.com/games/agdk/game-activity) is suitable if nearly all runtime code is native because it forwards lifecycle and input events to C/C++. A conventional Kotlin Activity plus JNI is simpler when the product needs a native Android library UI; choose one, not both.

### Audio and frame pacing

Android's [low-latency audio guidance](https://developer.android.com/games/sdk/oboe/low-latency-audio) recommends Oboe, low-latency mode, callbacks, the device's natural sample rate, and no blocking in callbacks. Balsini et al., **“Energy-efficient low-latency audio on android,” Journal of Systems and Software (2019), DOI [10.1016/j.jss.2019.03.013](https://doi.org/10.1016/j.jss.2019.03.013)** is directly relevant, but this review did not acquire its full text or reproduce device measurements.

Android's [Frame Pacing library](https://developer.android.com/games/sdk/frame-pacing) supports smooth OpenGL/Vulkan presentation. It does not remove the emulator-specific problem that GBA cadence is approximately 59.737 Hz; validate duplicate/drop behavior and audio drift on 60, 90, and 120 Hz devices.

### ROMs, saves, and lifecycle

- Use `ACTION_OPEN_DOCUMENT` and retain the user-granted URI permission; this avoids broad storage permission. See Android's [Storage Access Framework guidance](https://developer.android.com/training/data-storage/shared/documents-files).
- Pass a seekable file descriptor to native code or copy the selected ROM into private cache when the core requires a path.
- Identify games by a content hash, not only filename.
- Keep normal cartridge saves separate from save states.
- Write saves atomically (`temp` then rename). Include ROM hash, core name/version, state-schema version, and checksum in save-state metadata.
- Pause and flush on lifecycle loss; create a bounded autosave only after the core reaches a safe frame boundary.

The checkpointing idea is supported indirectly by de Winkel et al., **“Battery-Free Game Boy,” Proceedings of the ACM on Interactive, Mobile, Wearable and Ubiquitous Technologies (2020), DOI [10.1145/3411839](https://doi.org/10.1145/3411839)**. That paper reports selective state tracking for power-failure recovery on a battery-free Game Boy, not Android, so it is transfer evidence rather than a ready design.

### Input

Follow Android's [controller guidance](https://developer.android.com/games/sdk/game-controller/controller-input): distinguish `SOURCE_GAMEPAD`, `SOURCE_DPAD`, and `SOURCE_JOYSTICK`; handle button `KeyEvent`s and axis `MotionEvent`s; permit remapping. Keep the touch overlay as a separate input producer so physical and touch controls feed the same core input state.

## Open-source project assessment

Repository facts below come from GitHub APIs/default-branch trees retrieved on 2026-07-09. Stars are shown only as weak ecosystem signals, not quality proof. Every listed main repository had a Software Heritage origin record; this confirms archive registration, not freshness of the latest snapshot.

| Project | License | Current signals | Android/product fit | Recommendation |
|---|---|---|---|---|
| [mGBA](https://github.com/mgba-emu/mgba) | MPL-2.0 | 7,165 stars; pushed 2026-07-06; latest release 0.10.5 (2025-03-09); tests, fuzz/perf tools | Mature GBA core and libretro core; canonical tree has no Android frontend | **Best custom core** after NDK spike and license review |
| [SkyEmu](https://github.com/skylersaleh/SkyEmu) | MIT | 1,212 stars; pushed 2026-06-26; v5 (2026-02-12); Android build/deploy files | Android already works; project self-reports strong GBA conformance | **Fastest permissive MVP** |
| [Lemuroid](https://github.com/Swordfish90/Lemuroid) | GPL-3.0 | 4,057 stars; pushed 2026-07-08; 1.17.0 (2026-04-21); Android/TV/Play/F-Droid packaging | Production Android UX; uses mGBA through libretro | **Best GPL fork/reference** |
| [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) | GPL-3.0 | 111 stars; pushed/released 2026-05-24 | Android libretro host used by Lemuroid | Useful only when GPL is acceptable |
| [RetroArch](https://github.com/libretro/RetroArch) | GPL-3.0 | 13,332 stars; pushed 2026-07-09; Android CI; root `SECURITY.md` | Full reference frontend, but much larger than a GBA-only app | Study or distribute as-is; avoid as a custom-app base unless its breadth is required |
| [NanoBoyAdvance](https://github.com/nba-emu/NanoBoyAdvance) | GPL-3.0 | 1,304 stars; GitHub README says development moved to Codeberg; v1.8.3 (2026-05-10) | Accuracy-focused desktop C++/Qt/SDL core; no app-level Android port found | Accuracy reference, not shortest mobile base |
| [libretro/mgba](https://github.com/libretro/mgba) | MPL-2.0 | Mirror pushed 2026-04-03; no GitHub releases | mGBA packaging for libretro | Pin to canonical mGBA revision; do not treat mirror activity as an independent project |

Security posture is a gap: only RetroArch exposed a root `SECURITY.md` in the retrieved main-repository trees. Native ROM/archive parsing should therefore be treated as untrusted-input code and audited/fuzzed before release.

Detailed snapshot: `oss/repository-assessment.tsv`.

## Accuracy and testing

Use legally distributable homebrew/conformance ROMs in CI, then user-supplied commercial ROMs only for manual compatibility testing.

| Suite | License signal | Use |
|---|---|---|
| [mGBA suite](https://github.com/mgba-emu/suite) | MIT; pushed 2026-07-09 | Hardware timing and regression evidence |
| [gba-tests](https://github.com/jsmolka/gba-tests) | MIT; pushed 2025-04-19 | CPU/timing behavior |
| [FuzzARM](https://github.com/DenSinH/FuzzARM) | GPL-3.0; pushed 2022-06-14 | ARM instruction differential cases; review test-distribution implications |
| [armwrestler-gba-fixed](https://github.com/destoer/armwrestler-gba-fixed) | No detected license | Reference only until reuse/distribution permission is clarified |

SkyEmu self-reports full ArmWrestler, FuzzARM, `arm.gba`/`thumb.gba`, and 2020/2020 GBA Suite timing results with an official BIOS. NanoBoyAdvance also self-reports broad suite success. These are useful selection signals, not independent verification.

Minimum release gates:

1. pin the exact core revision and ABI set (`arm64-v8a` first; add others only when required);
2. run the MIT test ROM suites and retain output plus ROM hashes;
3. test at least one low-, mid-, and high-tier physical Android device across 60/90/120 Hz;
4. record audio underruns, frame-time distribution, sustained speed, memory, battery, and thermal throttling;
5. test pause/resume, process death, rotation/configuration changes, Bluetooth reconnect, malformed ROMs, and interrupted save writes;
6. compare screenshots/audio/state hashes against the selected upstream core where deterministic comparison is possible.

## What academia contributes

Direct academic evidence for “GBA emulation on modern Android” is sparse. The useful papers support narrower concerns:

- Richie and Ross, **“Cycle-accurate 8080 emulation using an ARM11 processor with dynamic binary translation,” MEMOCODE 2014, DOI [10.1109/memcod.2014.6961858](https://doi.org/10.1109/memcod.2014.6961858)**: cycle-accurate emulation with DBT is feasible in another CPU/host setting. It does **not** establish that a GBA Android MVP needs a JIT.
- Jebali and Potop-Butucaru, **“Ensuring Consistency between Cycle-Accurate and Instruction Set Simulators,” ACSD 2018, DOI [10.1109/acsd.2018.00019](https://doi.org/10.1109/acsd.2018.00019)**: supports comparing models at different timing fidelity, but the retrieved metadata does not provide GBA-specific results.
- Carta, **“Metadata and video games emulation: an effective bond to achieve authentic preservation?”, Records Management Journal (2017), DOI [10.1108/rmj-10-2016-0037](https://doi.org/10.1108/rmj-10-2016-0037)**: argues that technical metadata helps preserve the authenticity of complex game objects, supporting explicit ROM/core/state provenance.
- Bachell and Barr, **“Video Game Preservation in the UK,” International Journal of Digital Curation (2014), DOI [10.2218/ijdc.v9i2.294](https://doi.org/10.2218/ijdc.v9i2.294)**: identifies context, copyright, piracy, and cost as preservation barriers.
- Farrand, **“Emulation is the Most Sincere Form of Flattery: Retro Videogames, Rom Distribution and Copyright,” SSRN Electronic Journal (2012), DOI [10.2139/ssrn.2018883](https://doi.org/10.2139/ssrn.2018883)**: directly addresses emulation/ROM copyright but is not a substitute for current jurisdiction-specific advice.

The engineering conclusion therefore relies more heavily on maintained OSS and official Android documentation than on comparative academic benchmarks.

## Legal and store-policy boundary

This is not legal advice. Before distribution:

- do not bundle Nintendo ROMs, BIOS/firmware, keys, game screenshots, cover art, logos, or download links without rights;
- default to user-supplied ROMs and optional user-dumped BIOS;
- use original branding and store assets;
- provide complete third-party notices and satisfy MIT/MPL/GPL source obligations;
- review anti-circumvention and backup-copy rules for every target jurisdiction;
- obtain product counsel and Play review.

Google Play's [Intellectual Property policy](https://support.google.com/googleplay/android-developer/answer/9888072) disallows apps and accounts that infringe others' IP and warns that modified copyrighted content can still violate policy.

## Smallest execution plan

1. **Owner decision:** open GPL app, permissive fork, or closed product.
2. **One-device spike:** load a distributable test ROM through SAF; render; play Oboe audio; map one gamepad; pause/autosave/resume.
3. **Compare two candidates only:**
   - permissive: SkyEmu Android fork;
   - custom: mGBA core with the thinnest JNI host.
4. **Keep the winner using measurements:** sustained full speed, audio underruns, frame pacing, input latency, battery/thermal behavior, app size, and integration effort.
5. **Harden:** versioned atomic saves, malformed-input handling, conformance ROM CI, physical-device matrix, notices, and legal/store review.
6. **Only then add product features:** ROM library, touch customization, shaders, cheats, rewind, or cloud sync.

Dynamic recompilation, a new ARM interpreter, multi-system support, archive parsing, netplay, and cloud sync are deliberately excluded from the MVP. Add them only when a measured requirement justifies their cost.

## Method and limits

- Prepared 26 initial query variants covering GBA hardware, ARM emulation, Android audio/video/input, synchronization, testing, preservation, and legal issues.
- The first pass used the rforge `all` preset for 10 complete queries plus part of an eleventh; a focused 15-query pass then used OpenAlex, Crossref, and arXiv, while official Android documentation covered controller/platform details.
- Combined search files contain 3,133 record rows, 1,846 deduplication keys, and 1,381 unique DOI strings across 18 sources with results.
- Citation expansion succeeded for four seeds: the Android audio paper (33 edges), DBT paper (5), preservation-metadata paper (33), and Battery-Free Game Boy (50).
- GitHub metadata, default-branch trees, recent releases, README files, licenses, and Software Heritage origin records were collected for the principal projects and test suites.
- No full text was downloaded. No human inclusion/exclusion screening, extraction acceptance, legal approval, or final scientific claim approval was performed.
- The broad first pass was noisy and recorded 92 connector errors, including authentication failures, 403/404 responses, schema errors, and Semantic Scholar rate limiting. The focused OpenAlex/Crossref/arXiv pass completed without failures.

The weakest evidence gap is the absence of reproducible benchmarks of the candidate cores on the intended Android device matrix. A small implementation spike is now more valuable than another broad literature search.
