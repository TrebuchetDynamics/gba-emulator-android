# Game Boy / Game Boy Color support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import and play Game Boy (DMG) and Game Boy Color (GBC) ROMs alongside GBA — correct dimensions/aspect, L/R hidden for Game Boy, content-detected and badged — with zero GBA regression.

**Architecture:** Enable mGBA's GB core (`M_CORE_GB ON`); create the core by platform at session construction; query the core's real video dimensions instead of hard-coding 240×160 (the padded 256 stride stays constant so the GBA path is byte-identical, and the GB frame fits the existing buffer). A pure `RomSystem.detect(byte[])` picks the platform from ROM content. `ControlLayout` gains source-dimension + `hasShoulders` parameters (GBA defaults preserved). `EmulatorView` resizes its bitmap and builds the layout from the loaded ROM's dimensions/system. mGBA is used unmodified.

**Tech Stack:** C (JNI) + Java, Android `minSdk 24`, CMake/NDK, mGBA (vendored at `vendor/mgba`).

**Spec:** `docs/superpowers/specs/2026-07-16-gb-gbc-support-design.md`

## Global Constraints

- **No GBA regression:** a GBA ROM imports, plays, saves, and renders exactly as today (240×160, 3:2, L/R present, padded stride 256). Every native change must preserve the GBA path bit-for-bit.
- mGBA used **unmodified** — only the `M_CORE_GB` CMake option is flipped and existing mGBA APIs are called. No edits under `vendor/mgba`.
- Pure logic (`RomSystem.detect`, `ControlLayout` geometry) has **no `android.*` imports** and is JVM-tested. **`RomSystem` must not import `MgbaSession`** — `MgbaSession`'s static initializer calls `System.loadLibrary`, which throws in a JVM unit test. The `RomSystem → platform int` mapping lives in app (non-test) code.
- Shared key bits: GB and GBA both use bits 0–7 (A/B/SELECT/START/D-pad); L/R are bits 8–9, ignored by the GB core; `nativeRunFrame` already masks `keys & 0x3FF`. No input-path change.
- The private per-ROM filename stays `roms/<romId>.gba` for **all** systems (opaque suffix; content is detected at load) — preserves the existing romId↔path and file-scan contracts.
- minSdk 24; 0 lint errors; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Build/test (from repo root):
  - Unit tests: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest`
  - Lint: `mgba-android/gradlew -p mgba-android lintDebug`
  - Benchmark APK: `mgba-android/gradlew -p mgba-android :app:assembleBenchmark`
  - Instrumented (needs a device/AVD): `:core:connectedBenchmarkAndroidTest` (or the project's existing instrumented task)

## File Structure

- `mgba-android/core/CMakeLists.txt` — enable GB core.
- `mgba-android/core/src/main/cpp/mgba_android.c` — platform-selected core, variable video dims, `canCreateGbCore`, dimension getters.
- `mgba-android/core/src/main/java/.../mgba/MgbaSession.java` — platform constant + ctor, dimension accessors.
- `.../mgba/MgbaCore.java` — `canCreateGbCore`.
- `.../mgba/MgbaCoreInstrumentedTest.java` — GB core linked assertion.
- `.../app/RomSystem.java` (new) + `RomSystemTest.java` (new).
- `.../app/ControlLayout.java` (+ `ControlLayoutTest.java`).
- `.../app/EmulationRunner.java`, `EmulatorView.java`.
- `.../app/RomImporter.java`, `RomArchive.java`, `RomLibrary.java`, `LibraryActivity.java`.
- Test assets: a public-domain GB and GBC homebrew ROM under `mgba-android/core/src/androidTest/assets/`.

Main app dir: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/`
Test dir: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/`
Core dir: `mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/`

---

### Task 1: Native — enable the GB core, select by platform, variable video dimensions

**Files:**
- Modify: `mgba-android/core/CMakeLists.txt`
- Modify: `mgba-android/core/src/main/cpp/mgba_android.c`
- Modify: `.../mgba/MgbaSession.java`, `.../mgba/MgbaCore.java`
- Modify: `.../mgba/MgbaCoreInstrumentedTest.java`

**Interfaces:**
- Produces: `MgbaSession.PLATFORM_GBA = 0`, `PLATFORM_GB = 1`; `new MgbaSession(int platform)` (and `MgbaSession()` → GBA); `int videoWidth()`, `int videoHeight()`, `int framePixels()`; `MgbaCore.canCreateGbCore()`.
- Native ABI: `nativeCreate(int platform)`, `nativeVideoWidth(long)`, `nativeVideoHeight(long)`, `canCreateGbCore()`.

- [ ] **Step 1: Enable the GB core in CMake**

In `mgba-android/core/CMakeLists.txt`, change line 11 from:
```cmake
set(M_CORE_GB OFF CACHE BOOL "Do not build the Game Boy core" FORCE)
```
to:
```cmake
set(M_CORE_GB ON CACHE BOOL "Build the Game Boy core" FORCE)
```

- [ ] **Step 2: Native — session dimensions, platform-selected core, variable runFrame**

In `mgba-android/core/src/main/cpp/mgba_android.c`:

Add two fields to `struct MgbaSession` (after `bool loaded;`):
```c
    int videoWidth;
    int videoHeight;
```

Add a static helper above `nativeCreate` that maps the Java platform int to an mGBA platform and does the full core setup (extracted from the current `nativeCreate` body, now dimension-aware):
```c
static bool setupCore(struct MgbaSession* session, jint platform) {
    enum mPlatform mp = (platform == 1) ? mPLATFORM_GB : mPLATFORM_GBA;
    session->core = mCoreCreate(mp);
    if (!session->core || !session->core->init(session->core)) {
        if (session->core) {
            free(session->core);
            session->core = NULL;
        }
        return false;
    }

    mCoreInitConfig(session->core, NULL);
    struct mCoreOptions options = {
        .useBios = true,
        .fpsTarget = 60.0f,
        .sampleRate = AUDIO_SAMPLE_RATE,
        .volume = 0x100,
    };
    mCoreConfigLoadDefaults(&session->core->config, &options);
    mCoreLoadConfig(session->core);

    unsigned w = VIDEO_WIDTH;
    unsigned h = VIDEO_HEIGHT;
    session->core->desiredVideoDimensions(session->core, &w, &h);
    if ((int) w > VIDEO_STRIDE || (int) h > VIDEO_HEIGHT) {
        // Would overflow the fixed buffer; refuse rather than corrupt memory.
        session->core->deinit(session->core);
        session->core = NULL;
        return false;
    }
    session->videoWidth = (int) w;
    session->videoHeight = (int) h;

    session->core->setVideoBuffer(session->core, session->video, VIDEO_STRIDE);
    session->core->setAudioBufferSize(session->core, 2048);
    blip_set_rates(session->core->getAudioChannel(session->core, 0),
                   session->core->frequency(session->core), AUDIO_SAMPLE_RATE);
    blip_set_rates(session->core->getAudioChannel(session->core, 1),
                   session->core->frequency(session->core), AUDIO_SAMPLE_RATE);
    return true;
}
```

Replace the `nativeCreate` body to take the platform and use `setupCore`:
```c
JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeCreate(JNIEnv* env, jclass clazz, jint platform) {
    (void) env;
    (void) clazz;

    struct MgbaSession* session = calloc(1, sizeof(*session));
    if (!session) {
        return 0;
    }
    if (!setupCore(session, platform)) {
        free(session);
        return 0;
    }
    return (jlong) (uintptr_t) session;
}
```

Add dimension getters (place near `nativeFrameCounter`):
```c
JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoWidth(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    return session ? session->videoWidth : 0;
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoHeight(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    return session ? session->videoHeight : 0;
}
```

In `nativeRunFrame`, replace the fixed-dimension capacity check and copy loop. Change:
```c
    if (pixelCapacity < VIDEO_WIDTH * VIDEO_HEIGHT || audioCapacity < 2) {
```
to:
```c
    if (pixelCapacity < session->videoWidth * session->videoHeight || audioCapacity < 2) {
```
and change the copy loop:
```c
    for (size_t y = 0; y < VIDEO_HEIGHT; ++y) {
        for (size_t x = 0; x < VIDEO_WIDTH; ++x) {
            uint32_t native = session->video[y * VIDEO_STRIDE + x];
            output[y * VIDEO_WIDTH + x] = (jint) (0xFF000000U
```
to iterate the session's real dimensions (stride stays `VIDEO_STRIDE`):
```c
    for (size_t y = 0; y < (size_t) session->videoHeight; ++y) {
        for (size_t x = 0; x < (size_t) session->videoWidth; ++x) {
            uint32_t native = session->video[y * VIDEO_STRIDE + x];
            output[y * session->videoWidth + x] = (jint) (0xFF000000U
```
(keep the rest of the pixel-conversion expression exactly as-is.)

Add a `canCreateGbCore` JNI mirroring `canCreateGbaCore` (place right after `canCreateGbaCore`):
```c
JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbCore(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    struct mCore* core = mCoreCreate(mPLATFORM_GB);
    if (!core) {
        return JNI_FALSE;
    }
    if (!core->init(core)) {
        free(core);
        return JNI_FALSE;
    }
    core->deinit(core);
    return JNI_TRUE;
}
```

- [ ] **Step 3: `MgbaSession` — platform constant, ctor, dimension accessors**

In `MgbaSession.java`:

Add platform constants (near the KEY constants):
```java
    public static final int PLATFORM_GBA = 0;
    public static final int PLATFORM_GB = 1;
```

Add cached dimension fields (near `private boolean loaded;`):
```java
    private final int videoWidth;
    private final int videoHeight;
```

Replace the constructor with a platform-taking one plus a GBA-default delegate:
```java
    public MgbaSession() {
        this(PLATFORM_GBA);
    }

    public MgbaSession(int platform) {
        handle = nativeCreate(platform);
        if (handle == 0) {
            throw new IllegalStateException("Could not initialize the mGBA core");
        }
        videoWidth = nativeVideoWidth(handle);
        videoHeight = nativeVideoHeight(handle);
    }

    public int videoWidth() {
        return videoWidth;
    }

    public int videoHeight() {
        return videoHeight;
    }

    public int framePixels() {
        return videoWidth * videoHeight;
    }
```

Update the native decls:
```java
    private static native long nativeCreate(int platform);
    private static native int nativeVideoWidth(long handle);
    private static native int nativeVideoHeight(long handle);
```
Keep `VIDEO_WIDTH`/`VIDEO_HEIGHT`/`FRAME_PIXELS` constants (they remain the GBA/maximum bounds used elsewhere until Task 4 migrates call sites).

- [ ] **Step 4: `MgbaCore.canCreateGbCore`**

In `MgbaCore.java`, add:
```java
    /** Creates, initializes, and destroys a GB core as a native integration smoke test. */
    public static native boolean canCreateGbCore();
```

- [ ] **Step 5: Instrumented test — GB core linked, GBA unaffected**

In `MgbaCoreInstrumentedTest.java`, add a test asserting the GB core links (mirror the existing `canCreateGbaCore` test):
```java
    @Test
    public void canCreateGbCore() {
        assertTrue(MgbaCore.canCreateGbCore());
    }
```
Leave the existing `canCreateGbaCore` and session/load tests unchanged — they still use `new MgbaSession()` (GBA) and must still pass, proving no regression.

- [ ] **Step 6: Build the core + APK, run instrumented tests on a device/AVD**

Run: `mgba-android/gradlew -p mgba-android :core:assembleBenchmark :app:assembleBenchmark`
Expected: native build succeeds with the GB core compiled in; APK assembles.
Then on a booted AVD run the core instrumented tests (the project's existing instrumented task). Expected: `canCreateGbaCore` and `canCreateGbCore` both pass; existing GBA session/load tests pass.

- [ ] **Step 7: Commit**

```bash
git add mgba-android/core/CMakeLists.txt \
        mgba-android/core/src/main/cpp/mgba_android.c \
        mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java \
        mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaCore.java \
        mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java
git commit -m "feat(core): enable the Game Boy core with per-platform video dimensions

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Pure `RomSystem` content detection

**Files:**
- Create: `.../app/RomSystem.java`, `test/.../app/RomSystemTest.java`

**Interfaces:**
- Produces: `enum RomSystem { GBA, GB, GBC, UNKNOWN }`; `static RomSystem detect(byte[] rom)`; `boolean isGameBoy()`; `String badge()`.

- [ ] **Step 1: Write the failing test**

Create `RomSystemTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RomSystemTest {

    private static final int[] NINTENDO_LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83,
            0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E };

    private static byte[] gbaRom() {
        byte[] r = new byte[0x100];
        r[0xB2] = (byte) 0x96;
        return r;
    }

    private static byte[] gbRom(int cgbFlag) {
        byte[] r = new byte[0x150];
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            r[0x104 + i] = (byte) NINTENDO_LOGO[i];
        }
        r[0x143] = (byte) cgbFlag;
        return r;
    }

    @Test
    public void detectsGba() {
        assertEquals(RomSystem.GBA, RomSystem.detect(gbaRom()));
    }

    @Test
    public void detectsMonochromeGb() {
        assertEquals(RomSystem.GB, RomSystem.detect(gbRom(0x00)));
    }

    @Test
    public void detectsGbcByCgbFlag() {
        assertEquals(RomSystem.GBC, RomSystem.detect(gbRom(0x80)));
        assertEquals(RomSystem.GBC, RomSystem.detect(gbRom(0xC0)));
    }

    @Test
    public void unknownForGarbageOrShort() {
        assertEquals(RomSystem.UNKNOWN, RomSystem.detect(new byte[16]));
        assertEquals(RomSystem.UNKNOWN, RomSystem.detect(null));
        assertEquals(RomSystem.UNKNOWN, RomSystem.detect(new byte[0x150])); // no logo, no GBA byte
    }

    @Test
    public void isGameBoyGrouping() {
        assertTrue(RomSystem.GB.isGameBoy());
        assertTrue(RomSystem.GBC.isGameBoy());
        assertFalse(RomSystem.GBA.isGameBoy());
        assertFalse(RomSystem.UNKNOWN.isGameBoy());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*RomSystemTest'`
Expected: FAIL — `RomSystem` does not exist.

- [ ] **Step 3: Implement `RomSystem`**

Create `RomSystem.java`:
```java
package com.trebuchetdynamics.emulator.app;

/**
 * Which console a ROM targets, detected from the ROM's header bytes (content,
 * not file extension). No android.* imports — JVM-testable. Must NOT reference
 * MgbaSession (its static loadLibrary would break unit tests).
 */
enum RomSystem {
    GBA, GB, GBC, UNKNOWN;

    /** GB and GBC both run on mGBA's Game Boy core. */
    boolean isGameBoy() {
        return this == GB || this == GBC;
    }

    /** Short label for the library badge. */
    String badge() {
        switch (this) {
            case GBA: return "GBA";
            case GB: return "GB";
            case GBC: return "GBC";
            default: return "?";
        }
    }

    private static final int[] NINTENDO_LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83,
            0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E };

    static RomSystem detect(byte[] rom) {
        if (rom == null) {
            return UNKNOWN;
        }
        // GBA: fixed value 0x96 at 0xB2 within the 192-byte cartridge header.
        if (rom.length >= 0xC0 && (rom[0xB2] & 0xFF) == 0x96) {
            return GBA;
        }
        // GB/GBC: the Nintendo logo occupies 0x104..0x133; CGB flag at 0x143.
        if (rom.length >= 0x150 && matchesNintendoLogo(rom)) {
            int cgb = rom[0x143] & 0xFF;
            return (cgb == 0x80 || cgb == 0xC0) ? GBC : GB;
        }
        return UNKNOWN;
    }

    private static boolean matchesNintendoLogo(byte[] rom) {
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            if ((rom[0x104 + i] & 0xFF) != NINTENDO_LOGO[i]) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run to green, then lint**

Run: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*RomSystemTest' lintDebug`
Expected: PASS; lint 0 errors.

- [ ] **Step 5: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomSystem.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/RomSystemTest.java
git commit -m "feat(app): content-based ROM system detection

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `ControlLayout` — source aspect + shoulder toggle

**Files:**
- Modify: `.../app/ControlLayout.java` (+ `test/.../app/ControlLayoutTest.java`)

**Interfaces:**
- Produces: `static ControlLayout of(float w, float h, ControlOverrides overrides, float srcW, float srcH, boolean hasShoulders)`. Existing `of(w,h)` and `of(w,h,overrides)` delegate with GBA defaults (`srcW=MgbaSession.VIDEO_WIDTH, srcH=MgbaSession.VIDEO_HEIGHT, hasShoulders=true`).

- [ ] **Step 1: Write the failing tests**

Add to `ControlLayoutTest.java`:
```java
    @Test
    public void gameBoyLayoutHasNoShoulders() {
        ControlLayout gb = ControlLayout.of(1080f, 2340f, ControlOverrides.EMPTY, 160f, 144f, false);
        for (ControlLayout.Control c : gb.controls) {
            assertTrue("unexpected shoulder key=" + c.key,
                    c.key != MgbaSession.KEY_L && c.key != MgbaSession.KEY_R);
        }
        // A/B/SELECT/START/D-pad still present and hit-testable.
        assertTrue((gb.keysAt(controlOf(gb, MgbaSession.KEY_A).cx,
                controlOf(gb, MgbaSession.KEY_A).cy) & MgbaSession.KEY_A) != 0);
    }

    @Test
    public void gameBoyGameRectUsesTenNineAspect() {
        ControlLayout gb = ControlLayout.of(1080f, 2340f, ControlOverrides.EMPTY, 160f, 144f, false);
        float w = gb.gameRight - gb.gameLeft;
        float h = gb.gameBottom - gb.gameTop;
        assertEquals(160f / 144f, w / h, 0.02f);
    }

    @Test
    public void defaultOverloadsPreserveGbaLayout() {
        ControlLayout a = ControlLayout.of(1080f, 2340f);
        ControlLayout b = ControlLayout.of(1080f, 2340f, ControlOverrides.EMPTY,
                MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, true);
        assertEquals(a.controls.size(), b.controls.size());
        for (int i = 0; i < a.controls.size(); i++) {
            assertEquals(a.controls.get(i).key, b.controls.get(i).key);
            assertEquals(a.controls.get(i).cx, b.controls.get(i).cx, 1e-4f);
            assertEquals(a.controls.get(i).cy, b.controls.get(i).cy, 1e-4f);
        }
        // L and R are present in the GBA layout.
        boolean hasL = false, hasR = false;
        for (ControlLayout.Control c : a.controls) {
            if (c.key == MgbaSession.KEY_L) hasL = true;
            if (c.key == MgbaSession.KEY_R) hasR = true;
        }
        assertTrue(hasL && hasR);
    }

    // Helper (add if not already present in the test file).
    private static ControlLayout.Control controlOf(ControlLayout layout, int key) {
        for (ControlLayout.Control c : layout.controls) {
            if (c.key == key) return c;
        }
        throw new IllegalArgumentException("no control key=" + key);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlLayoutTest'`
Expected: FAIL — the 6-arg `of(...)` does not exist.

- [ ] **Step 3: Add the parameterized builder + thread through `portrait`/`landscape`**

In `ControlLayout.java`:

Replace the existing entry points so all three delegate into one:
```java
    static ControlLayout of(float width, float height) {
        return of(width, height, ControlOverrides.EMPTY);
    }

    static ControlLayout of(float width, float height, ControlOverrides overrides) {
        return of(width, height, overrides,
                MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, true);
    }

    static ControlLayout of(float width, float height, ControlOverrides overrides,
            float srcW, float srcH, boolean hasShoulders) {
        ControlLayout base = width > height
                ? landscape(width, height, srcW, srcH, hasShoulders)
                : portrait(width, height, srcW, srcH, hasShoulders);
        // ... existing override-application body unchanged (operates on `base`) ...
    }
```
(Keep the override-application logic from the touch-layout editor exactly as it is; only the `base` construction line changes to pass `srcW/srcH/hasShoulders`.)

Change `portrait` and `landscape` signatures to accept the new parameters:
```java
    private static ControlLayout portrait(float width, float height,
            float srcW, float srcH, boolean hasShoulders) { ... }
    private static ControlLayout landscape(float width, float height,
            float srcW, float srcH, boolean hasShoulders) { ... }
```

Inside each, replace `MgbaSession.VIDEO_HEIGHT`/`MgbaSession.VIDEO_WIDTH` in the **game-rect aspect** lines with `srcH`/`srcW`. Specifically:
- portrait: `float gameHeight = gameWidth * srcH / srcW;` and `gameWidth = gameHeight * srcW / srcH;`
- landscape: `float maxGameWidthByHeight = maxGameHeight * srcW / srcH;` and `float gameHeight = gameWidth * srcH / srcW;`

Wrap the two `controls.add(... KEY_L ...)` / `controls.add(... KEY_R ...)` blocks in **both** `portrait` and `landscape` in a guard:
```java
        if (hasShoulders) {
            controls.add(new Control(MgbaSession.KEY_L, "L", Shape.PILL, ...));
            controls.add(new Control(MgbaSession.KEY_R, "R", Shape.PILL, ...));
        }
```
(Leave the A/B/D-pad/SELECT/START adds and all other geometry unchanged. The `keysAt`, `contains`, and chip logic are untouched.)

- [ ] **Step 4: Run to green + full suite + lint**

Run: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest lintDebug`
Expected: new tests pass; all pre-existing `ControlLayoutTest` cases still pass (GBA layout unchanged); lint 0 errors.

- [ ] **Step 5: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java
git commit -m "feat(app): control layout adapts to source aspect and hides L/R for GB

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Runtime integration — play GB/GBC in the player

**Files:**
- Modify: `.../app/EmulationRunner.java`, `.../app/EmulatorView.java`

**Interfaces:**
- Consumes: `RomSystem.detect` (Task 2), `MgbaSession(platform)` / dimension accessors (Task 1), `ControlLayout.of(...,srcW,srcH,hasShoulders)` (Task 3).
- Produces: `EmulatorView.setVideoSize(int w, int h, boolean hasShoulders)`.

- [ ] **Step 1: `EmulatorView` — resizable bitmap + source dimensions/shoulders**

In `EmulatorView.java`:

Make the frame bitmap and source parameters mutable/volatile (replace the `final` bitmap field and its initializer):
```java
    private volatile Bitmap frame = Bitmap.createBitmap(
            MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
    private volatile int videoWidth = MgbaSession.VIDEO_WIDTH;
    private volatile int videoHeight = MgbaSession.VIDEO_HEIGHT;
    private volatile boolean hasShoulders = true;
```

Add a setter (called by the runner before the first frame is published):
```java
    void setVideoSize(int w, int h, boolean hasShoulders) {
        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            videoWidth = w;
            videoHeight = h;
        }
        this.hasShoulders = hasShoulders;
        postInvalidate();
    }
```

In `publishFrame`, replace the `MgbaSession.VIDEO_WIDTH`/`VIDEO_HEIGHT` uses with the fields:
```java
        frame.setPixels(pixels, 0, videoWidth, 0, 0, videoWidth, videoHeight);
```

In `onDraw`, replace the two `MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT` FeelMath calls with `videoWidth, videoHeight`, and build the layout with the source aspect + shoulder flag. Where the layout is currently built (`ControlLayout.of(getWidth(), getHeight(), activeOverrides(...))`), change to:
```java
        layout = ControlLayout.of(getWidth(), getHeight(), activeOverrides(getWidth(), getHeight()),
                videoWidth, videoHeight, hasShoulders);
```
Apply the same 6-arg form in `onSizeChanged`. (`onTouchEvent` still reads the `layout` field — unchanged.)

- [ ] **Step 2: `EmulationRunner` — detect system, platform core, dynamic buffer**

In `EmulationRunner.java`:

Add a helper to detect the system from the ROM file header (read enough bytes for the GB header at 0x150):
```java
    private static RomSystem detectSystem(File rom) {
        try (java.io.InputStream in = new java.io.FileInputStream(rom)) {
            byte[] head = new byte[0x150];
            int off = 0;
            int n;
            while (off < head.length && (n = in.read(head, off, head.length - off)) > 0) {
                off += n;
            }
            byte[] slice = off == head.length ? head : java.util.Arrays.copyOf(head, off);
            return RomSystem.detect(slice);
        } catch (java.io.IOException e) {
            return RomSystem.UNKNOWN;
        }
    }
```

At the top of `run()`, before creating the session, detect the system and create the session for its platform. Replace:
```java
        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(rom);
```
with:
```java
        RomSystem system = detectSystem(rom);
        int platform = system.isGameBoy() ? MgbaSession.PLATFORM_GB : MgbaSession.PLATFORM_GBA;
        try (MgbaSession session = new MgbaSession(platform)) {
            session.loadRom(rom);
            view.setVideoSize(session.videoWidth(), session.videoHeight(), !system.isGameBoy());
```

Replace the fixed pixel buffer:
```java
            int[] pixels = new int[MgbaSession.FRAME_PIXELS];
```
with:
```java
            int[] pixels = new int[session.framePixels()];
```
(Everything else — audio, the loop, save handling — is unchanged.)

- [ ] **Step 3: Build, unit tests, lint, APK**

Run: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests pass, lint 0 errors, APK assembles. A GBA ROM still plays at 240×160 with L/R (unchanged); a Game Boy ROM would now load into the GB core at 160×144 with L/R hidden.

- [ ] **Step 4: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "feat(app): play GB/GBC ROMs with per-system dimensions and controls

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Import & library — accept, detect, badge GB/GBC

**Files:**
- Modify: `.../app/RomImporter.java`, `RomArchive.java`, `RomLibrary.java`, `LibraryActivity.java`

**Interfaces:**
- Consumes: `RomSystem.detect`.
- Produces: `RomLibrary.Entry.system` (a `RomSystem`); `RomLibrary.record(romId, displayName, system, nowMs)`.

- [ ] **Step 1: Accept GB/GBC on import (content-validated)**

Read `RomImporter.java` and `RomArchive.java` first. The SAF picker is already `*/*`, so no picker change is needed.

- In `RomArchive` (zip handling): wherever it validates a candidate ROM's magic bytes as GBA, broaden acceptance to "content detects as a real ROM" using `RomSystem.detect(bytes) != RomSystem.UNKNOWN` (GBA **or** GB/GBC). Keep the single-ROM-per-zip and size-cap rules.
- In `RomImporter`: after obtaining the raw ROM bytes (raw file or extracted from zip), compute `RomSystem system = RomSystem.detect(bytes)`. If `system == RomSystem.UNKNOWN`, reject the import with the existing failure path/message ("Not a valid ROM"). Otherwise proceed. Keep the private filename `romId + ".gba"` (opaque). The display-name `.gba`/`.zip` stripping should also strip `.gb`/`.gbc` — update the suffix check:
```java
        if (lower.endsWith(".gba") || lower.endsWith(".gbc")
                || lower.endsWith(".gb") || lower.endsWith(".zip")) {
```
- Pass the detected `system` on to `RomLibrary.record(...)` (Step 2).

- [ ] **Step 2: `RomLibrary` — store and expose the system**

In `RomLibrary.java`:
- Add `final RomSystem system;` to `Entry` and set it in the constructor.
- In `list()`, read the stored system per ROM, defaulting to GBA for pre-existing entries:
```java
        RomSystem system = RomSystem.valueOfOrGba(meta.getProperty(romId + ".system"));
```
- In `record(...)`, add a `RomSystem system` parameter and persist it:
```java
    synchronized void record(String romId, String displayName, RomSystem system, long nowMs) throws IOException {
        Properties meta = loadMeta();
        meta.setProperty(romId + ".name", displayName);
        meta.setProperty(romId + ".system", system.name());
        meta.setProperty(romId + ".played", Long.toString(nowMs));
        storeMeta(meta);
    }
```
- Add a tolerant parser (a static helper on `RomLibrary` or `RomSystem`):
```java
    static RomSystem valueOfOrGba(String s) {
        if (s == null) return RomSystem.GBA;
        try { return RomSystem.valueOf(s); } catch (IllegalArgumentException e) { return RomSystem.GBA; }
    }
```
Update every `record(...)` call site (in `RomImporter`, and the play-time `touch`/re-record path if any) to pass the system. For the play-time last-played update that only bumps the timestamp, load the existing system from meta rather than overwriting it (preserve it).

- [ ] **Step 3: `LibraryActivity` — per-row badge**

In `LibraryActivity.java`, where each row renders the display name + relative time, add a small badge `TextView` (or append to the subtitle) showing `entry.system.badge()` (e.g. "GBA" / "GB" / "GBC"). Keep it minimal and consistent with the existing row style; no new layout files.

- [ ] **Step 4: Build, unit tests, lint, APK**

Run: `mgba-android/gradlew -p mgba-android :app:testDebugUnitTest lintDebug :app:assembleBenchmark`
Expected: tests pass, lint 0 errors, APK assembles. (If `RomLibrary`/`RomImporter` have unit tests, update their `record(...)` calls to the new signature and keep them green.)

- [ ] **Step 5: Commit**

```bash
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomImporter.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomArchive.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomLibrary.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LibraryActivity.java
git commit -m "feat(app): import and badge GB/GBC ROMs in the library

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Device verification

**Files:**
- Create: `docs/validation/gb-gbc-support-<date>.md`
- Add: a public-domain GB and GBC homebrew test ROM under `mgba-android/core/src/androidTest/assets/`.

**Interfaces:**
- Consumes: everything above.
- Produces: the multi-console exit evidence.

- [ ] **Step 1: Source public-domain test ROMs**

Obtain a small **public-domain / CC0 homebrew** Game Boy ROM and a Game Boy Color homebrew ROM (as with the existing MIT `hello.gba`). Place them under `mgba-android/core/src/androidTest/assets/` (e.g. `hello.gb`, `hello.gbc`). Record their source/license in the receipt. Do **not** use copyrighted commercial ROMs.

- [ ] **Step 2: Verify on a clean AVD**

Boot the AVD, install the benchmark APK, and push the three test ROMs (`hello.gba`, `hello.gb`, `hello.gbc`). For each:
1. **Import** via the library; confirm the row shows the correct **badge** (GBA / GB / GBC).
2. **Play**: confirm the game renders — the GB/GBC games at **160×144** with the **10:9** game rect and **no L/R** buttons; the GBA game still at **240×160** with L/R.
3. Confirm the GBC ROM renders in **colour** and the DMG ROM renders (mGBA default).
4. Confirm controls respond (tap a button; the in-game menu opens; save-state slots work) for at least one GB game.
5. Confirm the GBA ROM is **unchanged** end to end (import, play, controls, save states).

- [ ] **Step 3: No crashes + record**

```sh
adb -s emulator-5554 logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb -s emulator-5554 exec-out screencap -p > docs/validation/gb-gbc-support.png
adb -s emulator-5554 emu kill
```
Expected: 0 fatal/ANR.

- [ ] **Step 4: Write the receipt + commit**

Create `docs/validation/gb-gbc-support-<date>.md` recording device (AVD), the test-ROM sources/licenses, each check pass/fail with what was seen (badges, dimensions, L/R hidden, colour for GBC, GBA unchanged), the 0-fatal/ANR result, and the screenshot. Then:
```bash
git add docs/validation/gb-gbc-support-<date>.md docs/validation/gb-gbc-support.png \
        mgba-android/core/src/androidTest/assets/hello.gb mgba-android/core/src/androidTest/assets/hello.gbc
git commit -m "docs: record GB/GBC support device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** GB core enable + variable video (Task 1); content detection (Task 2); aspect + hide-L/R (Task 3); runtime play (Task 4); import/badge (Task 5); device (Task 6). Deferred items (DMG palette, SGB, link cable) stay out.
- **Type consistency:** `RomSystem` (`detect`/`isGameBoy`/`badge`/`GBA/GB/GBC/UNKNOWN`), `MgbaSession` (`PLATFORM_GBA/GB`, `MgbaSession(int)`, `videoWidth/videoHeight/framePixels`), `MgbaCore.canCreateGbCore`, `ControlLayout.of(...,srcW,srcH,hasShoulders)`, `EmulatorView.setVideoSize(w,h,hasShoulders)`, `RomLibrary.record(...,system,...)` are used consistently across tasks.
- **No-GBA-regression:** native keeps stride 256 + the fixed buffer, GBA dims 240×160, same config/audio — the GBA path is byte-identical; `MgbaSession()` still means GBA; `ControlLayout.of(w,h)` delegates to the GBA defaults (pinned by `defaultOverloadsPreserveGbaLayout`); `EmulatorView` defaults to 240×160/L-R until a ROM sets otherwise; the private filename and romId↔path contract are unchanged.
- **Purity/test isolation:** `RomSystem` and `ControlLayout` stay `android.*`-free; `RomSystem` deliberately does **not** import `MgbaSession` (avoids `loadLibrary` in JVM tests); the platform mapping lives in `EmulationRunner`.
- **Interlock (app builds after each task):** T1 keeps `MgbaSession()` working (GBA); T2 is additive; T3 overloads delegate to GBA defaults; T4 wires the runner/view to the new APIs; T5 wires import/library. Each leaves a compiling, runnable app; GB/GBC becomes fully usable after T5, device-verified in T6.
- **Buffer-fit safety:** the GB frame (160×144) fits the fixed 256×160 buffer; `setupCore` refuses (returns false → construction fails cleanly) if a core ever reports dimensions exceeding it.
- **Deliberately deferred:** DMG palette chooser, Super Game Boy borders, colourization, link cable, a rebalanced GB touch layout.
