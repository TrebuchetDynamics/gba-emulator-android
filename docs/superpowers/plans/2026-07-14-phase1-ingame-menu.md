# Phase 1: In-Game Menu Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a translucent in-game menu over the running game with multi-slot save/load state, a fast-forward toggle, reset, a settings-entry stub, and close — the centerpiece "feels like a real emulator" slice, shipping as v0.2.

**Architecture:** The emulated core is single-threaded and lives on the `mgba-emulation` thread inside `EmulationRunner`. The UI cannot call `MgbaSession` directly, so the menu enqueues commands (save/load/reset) that the emulation loop drains each iteration; results are posted back to the UI via a listener. Save-state bytes live in per-ROM slot files managed by a pure, JVM-tested `SaveStateStore`. Fast-forward is a volatile flag that shrinks the per-frame time budget and mutes audio. The menu itself is a programmatic translucent `View` overlaid on the existing `EmulatorView` inside a `FrameLayout`, opened by a new MENU handle chip added to the single-sourced `ControlLayout` geometry. Soft reset needs a new `MgbaSession.reset()` + one JNI method, since the core exposes save/load state but no reset.

**Tech Stack:** Android (Java, minSdk 24, targetSdk 35), JNI/C (mGBA 0.10.5), JUnit 4 (JVM unit tests), AndroidX instrumentation tests, Gradle via `mgba-android/gradlew`.

## Global Constraints

- mGBA stays an unmodified pinned submodule (`26b7884bc25a5933960f3cdcd98bac1ae14d42e2`, `0.10.5`); never edit files under `vendor/mgba`.
- No game or BIOS content is committed; instrumentation uses only the MIT `gba-tests` ROMs already in `mgba-android/core/src/androidTest/assets/`.
- `SaveStateStore` and any pacing helper must have **no `android.*` imports** so they unit-test on the JVM (the established pattern for `ControlLayout`, `RomArchive`, `FrameStats`).
- All on-screen control/chip geometry lives ONLY in `ControlLayout`; `EmulatorView` never recomputes a position. New chips add fields + an `isXHit` there, mirroring `isLoadHit`/`isNoticesHit`.
- Save/load state and reset execute ONLY on the emulation thread, never on the UI thread (`MgbaSession` is `synchronized` and single-threaded).
- Zero Android lint errors (`lintDebug`).
- The emulation loop must never die on a bad save state: a failed command reports an error and keeps running.
- Work on branch `mvp`. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

| File | Responsibility |
|---|---|
| `mgba-android/core/src/main/cpp/mgba_android.c` (modify) | `nativeReset` JNI — soft-reset the loaded core |
| `.../mgba/MgbaSession.java` (modify) | `reset()` + `nativeReset` declaration |
| `.../mgba/MgbaCoreInstrumentedTest.java` (modify) | instrumentation test for reset |
| `.../app/SaveStateStore.java` (create) | per-ROM save-state slot files; pure `java.io`, JVM-tested |
| `.../app/SaveStateStoreTest.java` (create) | JVM tests for the store |
| `.../app/EmulationRunner.java` (modify) | command queue (save/load/reset), fast-forward pacing + mute, `StateListener` |
| `.../app/EmulationRunnerTest.java` (create) | JVM tests for the pure `frameBudgetNanos` helper |
| `.../app/ControlLayout.java` (modify) | MENU handle chip geometry + `isMenuHit` |
| `.../app/ControlLayoutTest.java` (modify) | MENU chip non-overlap test |
| `.../app/EmulatorView.java` (modify) | draw the MENU chip; `requestMenu` callback |
| `.../app/InGameMenuView.java` (create) | translucent overlay: slots, fast-forward, reset, settings stub, close |
| `.../app/MainActivity.java` (modify) | host overlay in a `FrameLayout`; wire chip→open, buttons→runner, results→UI |
| `.../app/src/main/res/values/strings.xml` (modify) | menu strings |

Package for all `.../app/` files: `com.trebuchetdynamics.emulator.app`. Package for `.../mgba/` files: `com.trebuchetdynamics.emulator.mgba`.

---

### Task 1: Soft reset in the core

**Files:**
- Modify: `mgba-android/core/src/main/cpp/mgba_android.c`
- Modify: `mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java`
- Test: `mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java`

**Interfaces:**
- Produces: `MgbaSession.reset()` — soft-resets the loaded core (throws `IllegalStateException` if closed or no ROM loaded). Task 3 calls it from the emulation loop.

- [ ] **Step 1: Add the failing instrumentation test**

In `MgbaCoreInstrumentedTest.java`, add this test method (it uses the existing `hello.gba` asset helper pattern already used by `sessionRunsMitLicensedRomAndRestoresState` — match how that test obtains the session and runs frames):

```java
    @Test
    public void resetReturnsTheCoreToPowerOn() throws Exception {
        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(readAsset("hello.gba"));
            int[] pixels = new int[MgbaSession.FRAME_PIXELS];
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
            for (int i = 0; i < 30; i++) {
                session.runFrame(0, pixels, audio);
            }
            long before = session.frameCounter();
            assertTrue("frames should have advanced", before >= 30);
            session.reset();
            assertTrue("reset should return the frame counter toward power-on",
                    session.frameCounter() < before);
        }
    }
```
If `readAsset` is not the exact helper name in this file, use whatever helper the sibling `hello.gba` test uses to get a `byte[]`; do not add a new asset.

- [ ] **Step 2: Verify it fails to compile**

Run:
```sh
mgba-android/gradlew -p mgba-android :core:compileDebugAndroidTestSources
```
Expected: FAIL — `cannot find symbol: method reset()`.

- [ ] **Step 3: Add the native reset function**

In `mgba_android.c`, add this function next to the other `Java_..._MgbaSession_*` functions (it mirrors their `sessionFromHandle` + loaded-check pattern; the core is soft-reset with `core->reset`):

```c
JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeReset(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->core || !session->loaded) {
        return JNI_FALSE;
    }
    session->core->reset(session->core);
    return JNI_TRUE;
}
```

- [ ] **Step 4: Add the Java `reset()` and native declaration**

In `MgbaSession.java`, add this method after `loadState` (around line 112):
```java
    /** Soft-resets the loaded core to its power-on state. Cartridge save data persists. */
    public synchronized void reset() {
        requireLoaded();
        if (!nativeReset(handle)) {
            throw new IllegalStateException("mGBA could not reset the core");
        }
    }
```
And add the native declaration with the other `private static native` lines (near line 156):
```java
    private static native boolean nativeReset(long handle);
```

- [ ] **Step 5: Build and run the instrumentation test on a connected device**

Run (a device/emulator must be connected; use its serial):
```sh
ANDROID_SERIAL=<serial> mgba-android/gradlew -p mgba-android :core:connectedDebugAndroidTest
```
Expected: `BUILD SUCCESSFUL`; the report under `mgba-android/core/build/reports/androidTests/connected/` shows `resetReturnsTheCoreToPowerOn` passing and 0 failures. If no device is available to the implementer, STOP and report DONE_WITH_CONCERNS — the controller will run this on the physical device.

- [ ] **Step 6: Commit**

```sh
git add mgba-android/core/src/main/cpp/mgba_android.c \
        mgba-android/core/src/main/java/com/trebuchetdynamics/emulator/mgba/MgbaSession.java \
        mgba-android/core/src/androidTest/java/com/trebuchetdynamics/emulator/mgba/MgbaCoreInstrumentedTest.java
git commit -m "feat(core): expose soft reset for the in-game menu

MgbaSession.reset() soft-resets the loaded core (cartridge save persists);
instrumentation confirms the frame counter returns toward power-on.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: SaveStateStore — per-ROM slot files

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SaveStateStore.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SaveStateStoreTest.java`

**Interfaces:**
- Produces:
  - `SaveStateStore.SLOT_COUNT` (int constant = 4)
  - `new SaveStateStore(File statesRoot, String romId)`
  - `void write(int slot, byte[] state) throws IOException` — atomic write of slot 1..SLOT_COUNT
  - `byte[] read(int slot) throws IOException` — bytes, or `null` if the slot is empty
  - `boolean exists(int slot)`
  - `long timestamp(int slot)` — last-modified millis, or 0 if empty
  Task 3 (runner) and Task 5 (menu) both consume these.

- [ ] **Step 1: Write the failing tests**

Create `SaveStateStoreTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Test;

public class SaveStateStoreTest {
    private SaveStateStore store(String romId) throws IOException {
        File root = Files.createTempDirectory("states").toFile();
        root.deleteOnExit();
        return new SaveStateStore(root, romId);
    }

    @Test
    public void emptySlotReadsNullAndDoesNotExist() throws IOException {
        SaveStateStore s = store("rom1");
        assertNull(s.read(1));
        assertFalse(s.exists(1));
        assertEquals(0L, s.timestamp(1));
    }

    @Test
    public void writtenSlotRoundTrips() throws IOException {
        SaveStateStore s = store("rom1");
        byte[] state = {1, 2, 3, 4, 5};
        s.write(2, state);
        assertTrue(s.exists(2));
        assertArrayEquals(state, s.read(2));
        assertTrue(s.timestamp(2) > 0);
    }

    @Test
    public void slotsAreIndependent() throws IOException {
        SaveStateStore s = store("rom1");
        s.write(1, new byte[] {10});
        assertFalse(s.exists(2));
        assertArrayEquals(new byte[] {10}, s.read(1));
    }

    @Test
    public void differentRomsDoNotShareSlots() throws IOException {
        File root = Files.createTempDirectory("states").toFile();
        root.deleteOnExit();
        new SaveStateStore(root, "romA").write(1, new byte[] {1});
        assertFalse(new SaveStateStore(root, "romB").exists(1));
    }

    @Test
    public void overwritingASlotReplacesIt() throws IOException {
        SaveStateStore s = store("rom1");
        s.write(1, new byte[] {1, 1, 1});
        s.write(1, new byte[] {2, 2});
        assertArrayEquals(new byte[] {2, 2}, s.read(1));
    }

    @Test
    public void slotOutOfRangeIsRejected() throws IOException {
        SaveStateStore s = store("rom1");
        assertThrows(IllegalArgumentException.class, () -> s.write(0, new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> s.write(SaveStateStore.SLOT_COUNT + 1, new byte[] {1}));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*SaveStateStoreTest'
```
Expected: FAIL — `SaveStateStore` does not exist.

- [ ] **Step 3: Implement SaveStateStore**

Create `SaveStateStore.java`:
```java
package com.trebuchetdynamics.emulator.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Per-ROM save-state slots on disk. Pure java.io so it unit-tests on the JVM.
 * Each ROM gets a directory under {@code statesRoot}; each slot is one file,
 * written atomically (temp file + rename).
 */
final class SaveStateStore {
    static final int SLOT_COUNT = 4;

    private final File romDir;

    SaveStateStore(File statesRoot, String romId) {
        this.romDir = new File(statesRoot, romId);
    }

    void write(int slot, byte[] state) throws IOException {
        requireSlot(slot);
        if (state == null || state.length == 0) {
            throw new IllegalArgumentException("Refusing to write an empty state");
        }
        if (!romDir.isDirectory() && !romDir.mkdirs()) {
            throw new IOException("Could not create the save-state directory");
        }
        File target = slotFile(slot);
        File temp = new File(romDir, "slot" + slot + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(state);
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            temp.delete();
            throw e;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not commit the save state");
        }
    }

    byte[] read(int slot) throws IOException {
        requireSlot(slot);
        File file = slotFile(slot);
        if (!file.isFile()) {
            return null;
        }
        return Files.readAllBytes(file.toPath());
    }

    boolean exists(int slot) {
        requireSlot(slot);
        return slotFile(slot).isFile();
    }

    long timestamp(int slot) {
        requireSlot(slot);
        File file = slotFile(slot);
        return file.isFile() ? file.lastModified() : 0L;
    }

    private File slotFile(int slot) {
        return new File(romDir, "slot" + slot + ".state");
    }

    private static void requireSlot(int slot) {
        if (slot < 1 || slot > SLOT_COUNT) {
            throw new IllegalArgumentException("Slot must be 1.." + SLOT_COUNT);
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*SaveStateStoreTest'
```
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/SaveStateStore.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/SaveStateStoreTest.java
git commit -m "feat(app): per-ROM save-state slot store

Atomic per-slot files under a per-ROM directory; pure java.io, JVM-tested.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: EmulationRunner — commands, fast-forward, listener

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/EmulationRunnerTest.java`

**Interfaces:**
- Consumes: `MgbaSession.reset()` (Task 1); `SaveStateStore` (Task 2).
- Produces (all called from the UI thread):
  - `EmulationRunner(Context, EmulatorView, File rom, String romId, SaveStateStore states, ErrorListener errors, StateListener stateListener)`
  - `void postSaveState(int slot)`, `void postLoadState(int slot)`, `void postReset()`
  - `void setFastForward(boolean on)`, `boolean isFastForward()`
  - nested `interface StateListener { void onStateSaved(int slot); void onStateLoaded(int slot); void onStateError(String message); }`
  - static `long frameBudgetNanos(boolean fastForward)` (pure; unit-tested)
  Task 5 (MainActivity) constructs the runner with these two new args and calls the post/setFastForward methods.

- [ ] **Step 1: Write the failing unit test for the pacing helper**

Create `EmulationRunnerTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmulationRunnerTest {
    private static final long FRAME_NANOS = 16_743_000L;

    @Test
    public void normalSpeedUsesTheFullFrameBudget() {
        assertEquals(FRAME_NANOS, EmulationRunner.frameBudgetNanos(false));
    }

    @Test
    public void fastForwardShrinksTheBudget() {
        long ff = EmulationRunner.frameBudgetNanos(true);
        assertTrue("fast-forward budget must be smaller", ff < FRAME_NANOS);
        assertEquals(FRAME_NANOS / 4, ff);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*EmulationRunnerTest'
```
Expected: FAIL — `frameBudgetNanos` does not exist.

- [ ] **Step 3: Add fields, constructor args, command types, and public API**

In `EmulationRunner.java`:

Add imports near the top:
```java
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
```

Add the constant next to `FRAME_NANOS` (line 24):
```java
    private static final int FAST_FORWARD_SPEED = 4;
```

Add the listener interface next to `ErrorListener` (after line 22):
```java
    interface StateListener {
        void onStateSaved(int slot);
        void onStateLoaded(int slot);
        void onStateError(String message);
    }

    private enum CommandType { SAVE, LOAD, RESET }

    private static final class Command {
        final CommandType type;
        final int slot;
        Command(CommandType type, int slot) {
            this.type = type;
            this.slot = slot;
        }
    }
```

Add fields (after line 33, `private volatile boolean running = true;`):
```java
    private final SaveStateStore states;
    private final StateListener stateListener;
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private volatile boolean fastForward;
```

Replace the constructor (lines 36–44) with:
```java
    EmulationRunner(Context context, EmulatorView view, File rom, String romId,
                    SaveStateStore states, ErrorListener errors,
                    StateListener stateListener) {
        this.context = context.getApplicationContext();
        this.view = view;
        this.rom = rom;
        this.romId = romId;
        this.states = states;
        this.errors = errors;
        this.stateListener = stateListener;
        thread = new Thread(this, "mgba-emulation");
    }
```

Add the public control methods after `stop()` (after line 58):
```java
    void postSaveState(int slot) {
        commands.add(new Command(CommandType.SAVE, slot));
    }

    void postLoadState(int slot) {
        commands.add(new Command(CommandType.LOAD, slot));
    }

    void postReset() {
        commands.add(new Command(CommandType.RESET, 0));
    }

    void setFastForward(boolean on) {
        fastForward = on;
    }

    boolean isFastForward() {
        return fastForward;
    }

    static long frameBudgetNanos(boolean fastForward) {
        return fastForward ? FRAME_NANOS / FAST_FORWARD_SPEED : FRAME_NANOS;
    }
```

- [ ] **Step 4: Drain commands and apply fast-forward inside the loop**

Replace the `while (running) { ... }` body (lines 79–105) with (the marked lines are new/changed):
```java
            while (running) {
                applyCommands(session);                                       // new
                boolean ff = fastForward;                                     // new
                long budget = frameBudgetNanos(ff);                           // new
                long frameStart = SystemClock.elapsedRealtimeNanos();
                int audioFrames = session.runFrame(view.keys(), pixels, audio);
                if (audioFrames < 0) {
                    throw new IllegalStateException("mGBA failed to run a frame");
                }
                if (!ff && audioTrack != null && audioFrames > 0) {           // changed: mute on FF
                    audioTrack.write(audio, 0, audioFrames * 2, AudioTrack.WRITE_BLOCKING);
                }
                view.publishFrame(pixels);
                long now = SystemClock.elapsedRealtimeNanos();
                stats.record(now - frameStart);
                if (now >= nextPerfLog && stats.hasFrames()) {
                    int cumulativeUnderruns = audioTrack == null
                            ? 0 : audioTrack.getUnderrunCount();
                    Log.i(PERF_TAG, stats.summarizeAndReset(cumulativeUnderruns));
                    nextPerfLog = now + PERF_LOG_INTERVAL_NANOS;
                }

                nextFrame += budget;                                          // changed: was FRAME_NANOS
                long wait = nextFrame - SystemClock.elapsedRealtimeNanos();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else if (wait < -budget * 4) {                              // changed: was FRAME_NANOS
                    nextFrame = SystemClock.elapsedRealtimeNanos();
                }
            }
```

Add the `applyCommands` helper method after `run()` (after line 116, before `createAudioTrack`):
```java
    private void applyCommands(MgbaSession session) {
        Command command;
        while ((command = commands.poll()) != null) {
            try {
                switch (command.type) {
                    case SAVE:
                        states.write(command.slot, session.saveState());
                        stateListener.onStateSaved(command.slot);
                        break;
                    case LOAD:
                        byte[] state = states.read(command.slot);
                        if (state == null) {
                            stateListener.onStateError("Slot " + command.slot + " is empty");
                        } else {
                            session.loadState(state);
                            stateListener.onStateLoaded(command.slot);
                        }
                        break;
                    case RESET:
                        session.reset();
                        break;
                }
            } catch (IOException | RuntimeException e) {
                // A bad state must never kill the emulation loop.
                stateListener.onStateError(e.getMessage() == null
                        ? "Save-state operation failed" : e.getMessage());
            }
        }
    }
```

- [ ] **Step 5: Run the unit test to verify pass**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*EmulationRunnerTest'
```
Expected: PASS (2 tests).

- [ ] **Step 6: Build the app to confirm it compiles (constructor callers updated in Task 5)**

The only caller of the `EmulationRunner` constructor is `MainActivity`, updated in Task 5. To keep this task independently compilable, this task builds only the classes it owns via the unit-test compile above; the full `:app:assembleBenchmark` is run at the end of Task 5. Run lint on the changed file's module now to catch obvious issues:
```sh
mgba-android/gradlew -p mgba-android :app:compileDebugUnitTestJavaWithJavac
```
Expected: BUILD SUCCESSFUL (the unit-test source set compiles `EmulationRunner` and the test).

- [ ] **Step 7: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulationRunner.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/EmulationRunnerTest.java
git commit -m "feat(app): emulation-thread commands, fast-forward, save-state hooks

Save/load/reset are enqueued from the UI and applied on the emulation thread
(the core is single-threaded); a failed state operation reports an error and
never kills the loop. Fast-forward shrinks the frame budget 4x and mutes audio.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: MENU handle chip in the geometry

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java`

**Interfaces:**
- Consumes: existing `ControlLayout` header-band chip pattern (`loadLeft..loadBottom`, `noticesLeft..noticesBottom`, `isLoadHit`, `isNoticesHit`).
- Produces: `ControlLayout.menuLeft/menuTop/menuRight/menuBottom` fields + `boolean isMenuHit(float x, float y)`; `EmulatorView` draws a "MENU" chip and calls a `requestMenu` `Runnable` when tapped. Task 5 (`MainActivity`) passes `requestMenu` to open the overlay.

- [ ] **Step 1: Add the failing geometry test**

In `ControlLayoutTest.java`, add a test that the MENU chip does not overlap the other chips or the game rect, in both orientations (mirror the existing `loadAndNoticesChipsDoNotOverlapEachOtherOrTheGameScreen` test — reuse its `intersects` helper and the LANDSCAPE_*/PORTRAIT_* fixture constants):
```java
    @Test
    public void menuChipDoesNotOverlapOtherChipsOrTheGameScreen() {
        for (ControlLayout l : new ControlLayout[] {
                ControlLayout.of(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT),
                ControlLayout.of(PORTRAIT_WIDTH, PORTRAIT_HEIGHT) }) {
            assertFalse(intersects(
                    l.menuLeft, l.menuTop, l.menuRight, l.menuBottom,
                    l.gameLeft, l.gameTop, l.gameRight, l.gameBottom));
            assertFalse(intersects(
                    l.menuLeft, l.menuTop, l.menuRight, l.menuBottom,
                    l.noticesLeft, l.noticesTop, l.noticesRight, l.noticesBottom));
            assertFalse(intersects(
                    l.menuLeft, l.menuTop, l.menuRight, l.menuBottom,
                    l.loadLeft, l.loadTop, l.loadRight, l.loadBottom));
        }
    }
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlLayoutTest'
```
Expected: FAIL — `menuLeft` (etc.) do not exist.

- [ ] **Step 3: Add MENU chip fields and geometry**

In `ControlLayout.java`:

Add fields after `noticesBottom` (line 67):
```java
    final float menuLeft;
    final float menuTop;
    final float menuRight;
    final float menuBottom;
```

Add the four params to the private constructor signature (after the `notices*` params, line 72) and assign them (after the `noticesBottom` assignment, line 85):
```java
            float menuLeft, float menuTop, float menuRight, float menuBottom,
```
```java
        this.menuLeft = menuLeft;
        this.menuTop = menuTop;
        this.menuRight = menuRight;
        this.menuBottom = menuBottom;
```

In `portrait(...)`, after the `notices*` block (after line 131), compute the MENU chip to the LEFT of NOTICES (same header band, same height/top):
```java
        float menuWidth = unit * 0.22f;
        float menuRight = noticesLeft - 12;
        float menuLeft = menuRight - menuWidth;
        float menuTop = chipGap;
        float menuBottom = menuTop + chipHeight;
```
and pass them in the `return new ControlLayout(...)` (add after the `notices*` group, before `controls`):
```java
                menuLeft, menuTop, menuRight, menuBottom,
```

In `landscape(...)`, after the `notices*` block (after line 224), add the identical MENU computation:
```java
        float menuWidth = unit * 0.22f;
        float menuRight = noticesLeft - 12;
        float menuLeft = menuRight - menuWidth;
        float menuTop = chipGap;
        float menuBottom = menuTop + chipHeight;
```
and add `menuLeft, menuTop, menuRight, menuBottom,` to its `return new ControlLayout(...)` in the same position.

Add the hit-test after `isNoticesHit` (after line 298):
```java
    boolean isMenuHit(float x, float y) {
        float pad = 12f;
        return x >= menuLeft - pad && x <= menuRight + pad
                && y >= menuTop - pad && y <= menuBottom + pad * 2f;
    }
```

- [ ] **Step 4: Run to verify the geometry test passes**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*ControlLayoutTest'
```
Expected: PASS (all ControlLayoutTest tests, including the new one).

- [ ] **Step 5: Draw the MENU chip and wire its callback in EmulatorView**

In `EmulatorView.java`:

Add a field beside `requestNotices`:
```java
    private final Runnable requestMenu;
```

Update the two constructors: the no-arg convenience constructor and the main one. Change the main constructor signature to accept `Runnable requestMenu` as the last parameter, assign `this.requestMenu = requestMenu;`, and update the convenience constructor's `this(context, () -> {}, () -> {})` to `this(context, () -> {}, () -> {}, () -> {})`.

In `onDraw`, after the block that draws the NOTICES chip, add a MENU chip draw mirroring it (same fill `0xCC262A31`, white centered label), using `layout.menuLeft/menuTop/menuRight/menuBottom` and the label `"MENU"`.

In `onTouchEvent`, in the `ACTION_UP` branch where `isNoticesHit`/`isLoadHit` are checked, add a first check: `if (layout.isMenuHit(x, y)) { requestMenu.run(); }` before the notices check (so the ordering is menu → notices → load), keeping the existing `touchKeys = 0;` reset.

- [ ] **Step 6: Build, lint, unit tests**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass. (Full APK assembly happens in Task 5 once `MainActivity` provides the new `requestMenu` argument.)

- [ ] **Step 7: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/ControlLayout.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/ControlLayoutTest.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/EmulatorView.java
git commit -m "feat(app): add the MENU handle chip to the control geometry

Single-sourced in ControlLayout next to LOAD/NOTICES, with a non-overlap test in
both orientations; EmulatorView draws it and fires requestMenu on tap.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: The overlay view and MainActivity wiring

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/InGameMenuView.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`
- Modify: `mgba-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `EmulationRunner.postSaveState/postLoadState/postReset/setFastForward/isFastForward` + `StateListener` (Task 3); `SaveStateStore` (Task 2); `EmulatorView` `requestMenu` (Task 4).
- Produces: the running v0.2 experience. No later task consumes this beyond device verification (Task 6).

- [ ] **Step 1: Add menu strings**

In `res/values/strings.xml`, add inside `<resources>`:
```xml
    <string name="menu_save">Save state</string>
    <string name="menu_load">Load state</string>
    <string name="menu_fast_forward">Fast-forward</string>
    <string name="menu_reset">Reset</string>
    <string name="menu_settings">Settings (coming soon)</string>
    <string name="menu_close">Close</string>
    <string name="menu_slot_empty">Slot %1$d · empty</string>
    <string name="menu_slot_filled">Slot %1$d · saved</string>
```

- [ ] **Step 2: Create InGameMenuView**

Create `InGameMenuView.java` — a programmatic translucent overlay (matches the codebase's no-XML, build-views-in-code style seen in `NoticesActivity`). It exposes a `Listener` and a `bind(SaveStateStore, boolean fastForward)` that refreshes slot labels and the fast-forward toggle:
```java
package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

/** Translucent in-game menu drawn over the running game. Built in code. */
final class InGameMenuView extends LinearLayout {
    interface Listener {
        void onSaveSlot(int slot);
        void onLoadSlot(int slot);
        void onToggleFastForward();
        void onReset();
        void onClose();
    }

    private final Listener listener;
    private final Button fastForwardButton;
    private final Button[] saveButtons = new Button[SaveStateStore.SLOT_COUNT];
    private final Button[] loadButtons = new Button[SaveStateStore.SLOT_COUNT];

    InGameMenuView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xCC0E1014); // translucent scrim: game shows through
        // Swallow touches so they never reach the game behind the menu.
        setClickable(true);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);

        addView(sectionLabel(context.getString(R.string.menu_save)));
        addView(slotRow(context, saveButtons, true));
        addView(sectionLabel(context.getString(R.string.menu_load)));
        addView(slotRow(context, loadButtons, false));

        fastForwardButton = wideButton(context.getString(R.string.menu_fast_forward),
                v -> listener.onToggleFastForward());
        addView(fastForwardButton);
        addView(wideButton(context.getString(R.string.menu_reset), v -> listener.onReset()));
        Button settings = wideButton(context.getString(R.string.menu_settings), null);
        settings.setEnabled(false); // Phase 4 wires the settings screen.
        addView(settings);
        addView(wideButton(context.getString(R.string.menu_close), v -> listener.onClose()));
    }

    /** Refresh slot occupancy labels and the fast-forward toggle. */
    void bind(SaveStateStore store, boolean fastForward) {
        for (int i = 0; i < SaveStateStore.SLOT_COUNT; i++) {
            int slot = i + 1;
            String label = getContext().getString(
                    store.exists(slot) ? R.string.menu_slot_filled : R.string.menu_slot_empty,
                    slot);
            saveButtons[i].setText(String.valueOf(slot));
            loadButtons[i].setText(label);
            loadButtons[i].setEnabled(store.exists(slot));
        }
        fastForwardButton.setText(getContext().getString(R.string.menu_fast_forward)
                + (fastForward ? " ✓" : ""));
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private LinearLayout slotRow(Context context, Button[] into, boolean save) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < SaveStateStore.SLOT_COUNT; i++) {
            final int slot = i + 1;
            Button b = new Button(context);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                if (save) {
                    listener.onSaveSlot(slot);
                } else {
                    listener.onLoadSlot(slot);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(b, lp);
            into[i] = b;
        }
        return row;
    }

    private Button wideButton(String text, View.OnClickListener onClick) {
        Button b = new Button(getContext());
        b.setAllCaps(false);
        b.setText(text);
        if (onClick != null) {
            b.setOnClickListener(onClick);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(260), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
```

- [ ] **Step 3: Host the overlay in MainActivity and wire everything**

In `MainActivity.java`:

Add imports:
```java
import android.widget.FrameLayout;
```

Add fields next to `emulatorView` (line 24):
```java
    private FrameLayout root;
    private InGameMenuView menu;
    private SaveStateStore states;
```

In `onCreate`, replace `setContentView(emulatorView);` (line 48) with a `FrameLayout` that stacks the emulator and a hidden menu:
```java
        emulatorView = new EmulatorView(this, this::openRomPicker, this::openNotices, this::openMenu);
        root = new FrameLayout(this);
        root.addView(emulatorView);
        menu = new InGameMenuView(this, new InGameMenuView.Listener() {
            @Override public void onSaveSlot(int slot) {
                if (runner != null) runner.postSaveState(slot);
            }
            @Override public void onLoadSlot(int slot) {
                if (runner != null) runner.postLoadState(slot);
            }
            @Override public void onToggleFastForward() {
                if (runner != null) {
                    runner.setFastForward(!runner.isFastForward());
                    refreshMenu();
                }
            }
            @Override public void onReset() {
                if (runner != null) runner.postReset();
                closeMenu();
            }
            @Override public void onClose() {
                closeMenu();
            }
        });
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        emulatorView.requestFocus();
```

Add the menu control methods (near `openNotices`, after line 94). The MENU chip only makes sense while a game runs; `openMenu` no-ops when there is no runner:
```java
    private void openMenu() {
        if (runner == null || states == null) {
            return;
        }
        refreshMenu();
        menu.setVisibility(View.VISIBLE);
    }

    private void closeMenu() {
        menu.setVisibility(View.GONE);
    }

    private void refreshMenu() {
        if (states != null && runner != null) {
            menu.bind(states, runner.isFastForward());
        }
    }
```

In `startRunner()` (lines 190–204), build the `SaveStateStore` for the current ROM and pass it plus a `StateListener` into the runner. Replace the method body with:
```java
    private void startRunner() {
        if (!resumed || romFile == null || romId == null || runner != null) {
            return;
        }
        states = new SaveStateStore(new File(getFilesDir(), "states"), romId);
        runner = new EmulationRunner(this, emulatorView, romFile, romId, states,
                message -> runOnUiThread(() -> {
                    runner = null;
                    romFile = null;
                    romId = null;
                    romUri = null;
                    closeMenu();
                    emulatorView.setStatus(message + " — tap to choose another ROM");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }),
                new EmulationRunner.StateListener() {
                    @Override public void onStateSaved(int slot) {
                        runOnUiThread(() -> {
                            refreshMenu();
                            Toast.makeText(MainActivity.this,
                                    "Saved to slot " + slot, Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onStateLoaded(int slot) {
                        runOnUiThread(() -> {
                            closeMenu();
                            Toast.makeText(MainActivity.this,
                                    "Loaded slot " + slot, Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onStateError(String message) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                message, Toast.LENGTH_SHORT).show());
                    }
                });
        runner.start();
    }
```

In `stopRunner()` (lines 206–212), close the menu when emulation stops. Add `closeMenu();` as the first line inside the `if (runner != null)` block.

Add a back-button handler so the system Back closes the menu instead of leaving the app: add this override method to `MainActivity`:
```java
    @Override
    public void onBackPressed() {
        if (menu.getVisibility() == View.VISIBLE) {
            closeMenu();
            return;
        }
        super.onBackPressed();
    }
```

- [ ] **Step 4: Build, lint, unit tests, install**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass. The full app now compiles with the new constructor arguments.

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/InGameMenuView.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java \
        mgba-android/app/src/main/res/values/strings.xml
git commit -m "feat(app): in-game menu overlay (save/load slots, fast-forward, reset)

A translucent menu over the running game: multi-slot save/load state, a
fast-forward toggle, reset, a disabled settings stub (Phase 4), and close.
Opened by the MENU chip; Back closes it; commands run on the emulation thread.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Device verification

**Files:**
- Create: `docs/validation/phase1-ingame-menu-<date>.md` (use the actual execution date)

**Interfaces:**
- Consumes: everything above.
- Produces: the Phase 1 exit evidence — save/load/fast-forward/reset exercised on the physical device.

- [ ] **Step 1: Install and drive the flow on the device**

With the physical arm64 device unlocked and connected:
```sh
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell am start -n com.trebuchetdynamics.garnacha/com.trebuchetdynamics.emulator.app.MainActivity
```
Import a ROM and start play (a game with an in-game save is ideal for the load test). Then, tapping the MENU chip each time:
1. **Save** to slot 1; confirm the "Saved to slot 1" toast and that slot 1 now shows "saved".
2. Play forward a few seconds (visible progress), reopen the menu, **Load** slot 1; confirm the game visibly jumps back to the saved moment.
3. Toggle **Fast-forward**; confirm the game runs visibly faster and audio mutes; toggle off; confirm normal speed and audio return.
4. **Reset**; confirm the game returns to its boot/title screen and the menu closes.
5. Press **Close** and the system **Back** button; confirm each dismisses the menu without leaving the app.

- [ ] **Step 2: Confirm no crashes and capture evidence**

```sh
adb logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb exec-out screencap -p > docs/validation/phase1-menu-open.png
```
Expected: 0 fatal/ANR. The screenshot shows the translucent menu over the game.

- [ ] **Step 3: Write the receipt and commit**

Create `docs/validation/phase1-ingame-menu-<date>.md` recording: device, the five interactions above each marked pass/fail with what was observed, the 0-fatal/ANR result, and the screenshot reference. Then:
```sh
git add docs/validation/phase1-ingame-menu-<date>.md docs/validation/phase1-menu-open.png
git commit -m "docs: record Phase 1 in-game menu device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** the spec's Phase 1 lists "translucent panel over the running game" (Task 5 overlay), "multi-slot save/load state" (Tasks 2+3+5), "fast-forward toggle" (Tasks 3+5), "reset" (Tasks 1+3+5), "settings entry" (disabled stub in Task 5, screen deferred to Phase 4 per the spec), "close" (Task 5), opened by an "edge-swipe or handle" (handle chip, Task 4 — edge-swipe intentionally deferred as YAGNI; a handle is the reliable, consistent affordance).
- **Threading:** every `MgbaSession` call (save/load/reset) runs inside `applyCommands` on the emulation thread; the UI only enqueues and receives posted callbacks. This is the one hard correctness constraint and every task respects it.
- **Type consistency:** `SaveStateStore(File, String)`, `write(int,byte[])`, `read(int)→byte[]|null`, `SLOT_COUNT=4`, `StateListener.onStateSaved/onStateLoaded/onStateError`, `frameBudgetNanos(boolean)`, `isMenuHit`, and the `EmulationRunner`/`EmulatorView` constructor signatures are used identically across Tasks 2–5.
- **Deliberately deferred:** edge-swipe-to-open, the real settings screen (Phase 4), fast-forward speed selection (Phase 4), state thumbnails, and the disappear-the-chrome fade (Phase 2) are out of scope here.
