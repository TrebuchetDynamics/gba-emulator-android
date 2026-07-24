# Phase 3: ROM Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-ROM flow with a library-first app — a launcher screen listing imported ROMs (most-recently-played first) with import and delete, that launches the player for a chosen ROM — shipping as v0.4.

**Architecture:** A new `RomLibrary` (pure java.io) enumerates the private ROM files and tracks a display name + last-played time per ROM in a properties index, and cascades a delete to the ROM plus its cartridge save and save states. The SAF import logic moves out of `MainActivity` into a reusable `RomImporter` that also records the imported ROM into the library. A new `LibraryActivity` becomes the launcher: it lists the library, imports, and deletes, and starts `MainActivity` with a ROM id. `MainActivity` is refactored to play the ROM named by that id (and bump its last-played time), dropping its own picker/empty-state. Control geometry, the in-game menu, save states, and play-feel are untouched.

**Tech Stack:** Android (Java, minSdk 24, targetSdk 35), Storage Access Framework, programmatic Views (the codebase's no-XML pattern), `java.util.Properties`, JUnit 4 (JVM), Gradle via `mgba-android/gradlew`.

## Global Constraints

- The private data layout is fixed and shared: ROMs at `filesDir/roms/<romId>.gba`, cartridge saves at `filesDir/saves/<romId>.sav`, save states at `filesDir/states/<romId>/slotN.state`. `romId` is the ROM's lowercase SHA-256 hex. A delete must remove all four (rom, `.sav`, `.sav.tmp`, states dir) plus the library index entry.
- `RomLibrary` must have NO `android.*` imports so it unit-tests on the JVM (the pattern used by `SaveStateStore`, `RomArchive`, `ControlLayout`, `FeelMath`).
- No cover-art / thumbnails (deferred post-1.0). The list is text: display name + last-played.
- No game/BIOS content committed. Existing `RomArchive.extractRom` (zip-aware, size-capped, SHA-256) is reused unchanged for import.
- minSdk 24; zero Android lint errors (`lintDebug`).
- Work on branch `mvp`. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

| File | Responsibility |
|---|---|
| `.../app/RomLibrary.java` (create) | enumerate ROMs + per-ROM metadata (name, last-played); cascade delete; pure java.io |
| `.../app/RomLibraryTest.java` (create) | JVM tests for the above |
| `.../app/RomImporter.java` (create) | SAF Uri → extracted hash-named private ROM + library record (moved from MainActivity) |
| `.../app/LibraryActivity.java` (create) | launcher: list, import, delete, launch player |
| `.../app/MainActivity.java` (modify) | play the ROM named by an intent id; drop the picker/empty-state |
| `.../app/src/main/AndroidManifest.xml` (modify) | LibraryActivity = launcher; MainActivity internal |
| `.../app/src/main/res/values/strings.xml` (modify) | library strings |

Package for all `.../app/` files: `com.trebuchetdynamics.emulator.app`.

---

### Task 1: RomLibrary — enumeration, metadata, cascade delete

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomLibrary.java`
- Test: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/RomLibraryTest.java`

**Interfaces:**
- Produces:
  - `RomLibrary(File filesDir)`
  - nested `RomLibrary.Entry` — `{String romId, String displayName, long lastPlayedMs, File romFile}`
  - `List<Entry> list()` — imported ROMs, most-recently-played first (case-insensitive name tie-break)
  - `void record(String romId, String displayName, long nowMs) throws IOException` — set name + last-played (on import)
  - `void touch(String romId, long nowMs) throws IOException` — update last-played (on play)
  - `boolean exists(String romId)`
  - `void delete(String romId) throws IOException` — remove rom + `.sav` + `.sav.tmp` + states dir + index entry
  Tasks 2–4 consume these.

- [ ] **Step 1: Write the failing tests**

Create `RomLibraryTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class RomLibraryTest {
    private File filesDir;

    @Before
    public void setUp() throws IOException {
        filesDir = Files.createTempDirectory("files").toFile();
        filesDir.deleteOnExit();
    }

    private void writeRom(String romId) throws IOException {
        File roms = new File(filesDir, "roms");
        roms.mkdirs();
        File f = new File(roms, romId + ".gba");
        Files.write(f.toPath(), new byte[] {1, 2, 3});
    }

    @Test
    public void emptyLibraryListsNothing() {
        assertTrue(new RomLibrary(filesDir).list().isEmpty());
    }

    @Test
    public void recordThenListReturnsNamedEntry() throws IOException {
        writeRom("aaa1");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("aaa1", "Zelda", 5000L);
        List<RomLibrary.Entry> entries = lib.list();
        assertEquals(1, entries.size());
        assertEquals("aaa1", entries.get(0).romId);
        assertEquals("Zelda", entries.get(0).displayName);
        assertEquals(5000L, entries.get(0).lastPlayedMs);
        assertTrue(entries.get(0).romFile.isFile());
    }

    @Test
    public void listSortsMostRecentlyPlayedFirst() throws IOException {
        writeRom("older");
        writeRom("newer");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("older", "Older", 1000L);
        lib.record("newer", "Newer", 2000L);
        List<RomLibrary.Entry> entries = lib.list();
        assertEquals("newer", entries.get(0).romId);
        assertEquals("older", entries.get(1).romId);
    }

    @Test
    public void touchBumpsOrder() throws IOException {
        writeRom("a");
        writeRom("b");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("a", "A", 1000L);
        lib.record("b", "B", 2000L);
        lib.touch("a", 3000L); // a now newest
        assertEquals("a", lib.list().get(0).romId);
    }

    @Test
    public void unnamedRomStillListsWithFallback() throws IOException {
        writeRom("deadbeefdeadbeef");
        List<RomLibrary.Entry> entries = new RomLibrary(filesDir).list();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).displayName.startsWith("deadbeefdead"));
        assertEquals(0L, entries.get(0).lastPlayedMs);
    }

    @Test
    public void listIgnoresNonGbaFiles() throws IOException {
        File roms = new File(filesDir, "roms");
        roms.mkdirs();
        Files.write(new File(roms, "notes.txt").toPath(), new byte[] {1});
        assertTrue(new RomLibrary(filesDir).list().isEmpty());
    }

    @Test
    public void deleteRemovesRomSavesStatesAndIndex() throws IOException {
        writeRom("victim");
        // a cartridge save and a save-state slot for the same rom
        File saves = new File(filesDir, "saves");
        saves.mkdirs();
        Files.write(new File(saves, "victim.sav").toPath(), new byte[] {9});
        File states = new File(new File(filesDir, "states"), "victim");
        states.mkdirs();
        Files.write(new File(states, "slot1.state").toPath(), new byte[] {9});

        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("victim", "Victim", 1000L);
        assertTrue(lib.exists("victim"));

        lib.delete("victim");

        assertFalse(lib.exists("victim"));
        assertFalse(new File(saves, "victim.sav").exists());
        assertFalse(states.exists());
        assertTrue(lib.list().isEmpty()); // index entry gone too
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*RomLibraryTest'
```
Expected: FAIL — `RomLibrary` does not exist.

- [ ] **Step 3: Implement RomLibrary**

Create `RomLibrary.java`:
```java
package com.trebuchetdynamics.emulator.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * The user's imported-ROM library: enumerates the private ROM files, tracks a
 * display name and last-played time per ROM in a properties index, and cascades
 * a delete to the ROM plus its cartridge save and save states. Pure java.io /
 * java.util (no android.*), so it unit-tests on the JVM.
 */
final class RomLibrary {
    static final class Entry {
        final String romId;
        final String displayName;
        final long lastPlayedMs;
        final File romFile;

        Entry(String romId, String displayName, long lastPlayedMs, File romFile) {
            this.romId = romId;
            this.displayName = displayName;
            this.lastPlayedMs = lastPlayedMs;
            this.romFile = romFile;
        }
    }

    private final File romsDir;
    private final File savesDir;
    private final File statesDir;
    private final File metaFile;

    RomLibrary(File filesDir) {
        this.romsDir = new File(filesDir, "roms");
        this.savesDir = new File(filesDir, "saves");
        this.statesDir = new File(filesDir, "states");
        this.metaFile = new File(filesDir, "library.properties");
    }

    /** Imported ROMs, most-recently-played first (case-insensitive name tie-break). */
    List<Entry> list() {
        Properties meta = loadMeta();
        List<Entry> entries = new ArrayList<>();
        File[] files = romsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (!f.isFile() || !name.endsWith(".gba")) {
                    continue;
                }
                String romId = name.substring(0, name.length() - ".gba".length());
                String displayName = meta.getProperty(romId + ".name", shortId(romId));
                long played = parseLong(meta.getProperty(romId + ".played"));
                entries.add(new Entry(romId, displayName, played, f));
            }
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byTime = Long.compare(b.lastPlayedMs, a.lastPlayedMs);
                return byTime != 0 ? byTime : a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        return entries;
    }

    void record(String romId, String displayName, long nowMs) throws IOException {
        Properties meta = loadMeta();
        meta.setProperty(romId + ".name", displayName);
        meta.setProperty(romId + ".played", Long.toString(nowMs));
        storeMeta(meta);
    }

    void touch(String romId, long nowMs) throws IOException {
        Properties meta = loadMeta();
        meta.setProperty(romId + ".played", Long.toString(nowMs));
        storeMeta(meta);
    }

    boolean exists(String romId) {
        return new File(romsDir, romId + ".gba").isFile();
    }

    void delete(String romId) throws IOException {
        new File(romsDir, romId + ".gba").delete();
        new File(savesDir, romId + ".sav").delete();
        new File(savesDir, romId + ".sav.tmp").delete();
        deleteRecursively(new File(statesDir, romId));
        Properties meta = loadMeta();
        meta.remove(romId + ".name");
        meta.remove(romId + ".played");
        storeMeta(meta);
    }

    private Properties loadMeta() {
        Properties p = new Properties();
        if (metaFile.isFile()) {
            try (FileInputStream in = new FileInputStream(metaFile)) {
                p.load(in);
            } catch (IOException ignored) {
                // A corrupt index must not break enumeration.
            }
        }
        return p;
    }

    private void storeMeta(Properties meta) throws IOException {
        File dir = metaFile.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        File temp = new File(metaFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            meta.store(out, "Garnacha Boy library");
            out.getFD().sync();
        }
        if (!temp.renameTo(metaFile)) {
            temp.delete();
            throw new IOException("Could not save the library index");
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        file.delete();
    }

    private static long parseLong(String s) {
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String shortId(String romId) {
        return romId.length() > 12 ? romId.substring(0, 12) + "…" : romId;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run:
```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*RomLibraryTest'
```
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomLibrary.java \
        mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/RomLibraryTest.java
git commit -m "feat(app): ROM library index — enumerate, metadata, cascade delete

Pure java.io: lists private ROMs most-recently-played first, tracks display
name + last-played in a properties index, and deletes a ROM with its cartridge
save and save states. JVM-tested.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: RomImporter — extract SAF import and record into the library

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomImporter.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`

**Interfaces:**
- Consumes: `RomArchive.extractRom(InputStream, OutputStream, long)` (existing); `RomLibrary.record(String, String, long)` (Task 1).
- Produces:
  - `RomImporter(Context context, RomLibrary library)`
  - nested `RomImporter.Result` — `{String romId, File romFile, String displayName}`
  - `Result importRom(Uri uri) throws IOException` — resolves the SAF display name, extracts the ROM to `filesDir/roms/<sha256>.gba` atomically, records it in the library with `now = System.currentTimeMillis()`, returns the result. Runs on a background thread (caller's responsibility, as today).
  Task 4 (LibraryActivity) consumes this.

- [ ] **Step 1: Create RomImporter with the import logic moved from MainActivity**

Create `RomImporter.java` (this is the current `MainActivity.importRom` + `hex` logic, plus SAF display-name resolution and a `library.record(...)` call):
```java
package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Imports a user-selected document into the private ROM store and the library. */
final class RomImporter {
    private static final int MAX_ROM_BYTES = 32 * 1024 * 1024;

    static final class Result {
        final String romId;
        final File romFile;
        final String displayName;

        Result(String romId, File romFile, String displayName) {
            this.romId = romId;
            this.romFile = romFile;
            this.displayName = displayName;
        }
    }

    private final Context context;
    private final RomLibrary library;

    RomImporter(Context context, RomLibrary library) {
        this.context = context.getApplicationContext();
        this.library = library;
    }

    Result importRom(Uri uri) throws IOException {
        File directory = new File(context.getFilesDir(), "roms");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private ROM directory");
        }
        File temporary = File.createTempFile("import-", ".tmp", directory);
        byte[] hash;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IOException("Content provider returned no data");
            }
            hash = RomArchive.extractRom(input, output, MAX_ROM_BYTES);
            output.getFD().sync();
        } catch (IOException | RuntimeException e) {
            temporary.delete();
            throw e;
        }

        String romId = hex(hash);
        File destination = new File(directory, romId + ".gba");
        if (destination.isFile()) {
            temporary.delete();
        } else if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not finish private ROM import");
        }

        String displayName = resolveDisplayName(uri, romId);
        library.record(romId, displayName, System.currentTimeMillis());
        return new Result(romId, destination, displayName);
    }

    private String resolveDisplayName(Uri uri, String romId) {
        String name = null;
        try (Cursor c = context.getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
                name = c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the id below.
        }
        if (name == null || name.trim().isEmpty()) {
            return romId.length() > 12 ? romId.substring(0, 12) : romId;
        }
        name = name.trim();
        // Strip a trailing .gba / .zip for a cleaner title.
        String lower = name.toLowerCase();
        if (lower.endsWith(".gba") || lower.endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            result[i * 2] = digits[(bytes[i] >>> 4) & 0xF];
            result[i * 2 + 1] = digits[bytes[i] & 0xF];
        }
        return new String(result);
    }
}
```

- [ ] **Step 2: Make MainActivity delegate its import to RomImporter**

In `MainActivity.java`, keep the existing async structure but replace the private `importRom(Uri)` + `hex(...)` + `ImportedRom` usage with a `RomImporter` call. Specifically:

Add a field:
```java
    private RomLibrary library;
```
In `onCreate`, initialize it before first use (after `super.onCreate` work):
```java
        library = new RomLibrary(getFilesDir());
```
In `importRomAsync(Uri uri)`, change the background body from calling the private `importRom(uri)` returning an `ImportedRom`, to:
```java
                RomImporter.Result imported = new RomImporter(this, library).importRom(uri);
```
and update the success callback to use `imported.romFile` / `imported.romId` (same field names as the old `ImportedRom.file`/`.id` → now `imported.romFile`/`imported.romId`).

Delete the now-unused private `importRom(Uri)` method, the `hex(byte[])` method, the `ImportedRom` nested class, and the `MAX_ROM_BYTES` constant (all moved into `RomImporter`). Remove the now-unused imports (`FileOutputStream`, `InputStream`, and any others left dangling) — let the compiler/lint tell you which.

- [ ] **Step 3: Build, lint, unit tests**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass (prior + Task 1's 7 RomLibrary). The app still builds and imports as before, now also recording each import into the library index.

- [ ] **Step 4: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/RomImporter.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java
git commit -m "refactor(app): extract ROM import into RomImporter, record into library

The SAF import (zip-aware extract, atomic hash-named private file) moves out of
MainActivity into a reusable RomImporter that also resolves the document's
display name and records the ROM in the library index. Behavior unchanged.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: MainActivity plays a ROM named by intent id

**Files:**
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`

**Interfaces:**
- Consumes: `RomLibrary.touch(String, long)` (Task 1).
- Produces: `MainActivity.EXTRA_ROM_ID` (String extra key). When launched with it, `MainActivity` plays `filesDir/roms/<romId>.gba` and bumps its last-played time. Launched WITHOUT it, the existing picker flow still works (so the app remains runnable standalone until Task 4 flips the launcher). Task 4 launches `MainActivity` with this extra.

- [ ] **Step 1: Add the id-play path**

In `MainActivity.java`:

Add the extra key constant near the other constants (with `OPEN_ROM`):
```java
    public static final String EXTRA_ROM_ID = "com.trebuchetdynamics.garnacha.ROM_ID";
```

In `onCreate`, after `library = new RomLibrary(getFilesDir());`, if an id was passed, resolve and stage that ROM for play:
```java
        String requestedRomId = getIntent().getStringExtra(EXTRA_ROM_ID);
        if (requestedRomId != null && library.exists(requestedRomId)) {
            romId = requestedRomId;
            romFile = new File(new File(getFilesDir(), "roms"), requestedRomId + ".gba");
            try {
                library.touch(requestedRomId, System.currentTimeMillis());
            } catch (IOException ignored) {
                // Non-fatal: last-played ordering is best-effort.
            }
        }
```
(`romId`/`romFile` are existing fields; `onResume` already calls `startRunner()` when `romFile` is a real file, so a passed id starts playing without further changes.)

- [ ] **Step 2: Build, lint, unit tests**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass. Launched normally the app behaves as before; launched with `EXTRA_ROM_ID` it will play that ROM (exercised on device after Task 4).

- [ ] **Step 3: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java
git commit -m "feat(app): MainActivity can play a ROM named by intent id

Launched with EXTRA_ROM_ID, the player loads that library ROM and bumps its
last-played time; the existing flow is unchanged when no id is passed.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: LibraryActivity as launcher; MainActivity becomes player-only

**Files:**
- Create: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LibraryActivity.java`
- Modify: `mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java`
- Modify: `mgba-android/app/src/main/AndroidManifest.xml`
- Modify: `mgba-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `RomLibrary` (Task 1), `RomImporter` (Task 2), `MainActivity.EXTRA_ROM_ID` (Task 3).
- Produces: the v0.4 library-first app. No later task consumes this beyond device verification.

- [ ] **Step 1: Add library strings**

In `res/values/strings.xml`, add inside `<resources>`:
```xml
    <string name="library_title">Garnacha Boy</string>
    <string name="library_import">Import ROM</string>
    <string name="library_empty">No games yet.\nTap Import to add a GBA ROM.</string>
    <string name="library_never">never played</string>
    <string name="library_importing">Importing…</string>
    <string name="library_import_failed">Could not import the selected ROM</string>
    <string name="library_delete_title">Delete game?</string>
    <string name="library_delete_message">This removes %1$s and its saves and save states. This cannot be undone.</string>
    <string name="library_delete_confirm">Delete</string>
    <string name="library_cancel">Cancel</string>
```

- [ ] **Step 2: Create LibraryActivity**

Create `LibraryActivity.java` — the launcher. Programmatic Views (matching `NoticesActivity`), a `ScrollView` of tappable rows rebuilt on resume:
```java
package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

public final class LibraryActivity extends Activity {
    private static final int OPEN_ROM = 200;

    private RomLibrary library;
    private LinearLayout listContainer;
    private TextView emptyView;
    private volatile boolean importing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = new RomLibrary(getFilesDir());
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1014);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.library_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        Button importButton = new Button(this);
        importButton.setText(R.string.library_import);
        importButton.setAllCaps(false);
        importButton.setOnClickListener(v -> openRomPicker());
        header.addView(importButton);
        root.addView(header);

        emptyView = new TextView(this);
        emptyView.setText(R.string.library_empty);
        emptyView.setTextColor(0xFF9AA0AA);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(48), 0, 0);
        root.addView(emptyView);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listContainer);
        root.addView(scroll);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        listContainer.removeAllViews();
        List<RomLibrary.Entry> entries = library.list();
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (RomLibrary.Entry entry : entries) {
            listContainer.addView(rowFor(entry));
        }
    }

    private View rowFor(RomLibrary.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        row.setPadding(p, p, p, p);
        row.setClickable(true);

        TextView name = new TextView(this);
        name.setText(entry.displayName);
        name.setTextColor(Color.WHITE);
        name.setTextSize(18);

        TextView sub = new TextView(this);
        sub.setText(entry.lastPlayedMs > 0
                ? DateUtils.getRelativeTimeSpanString(entry.lastPlayedMs)
                : getString(R.string.library_never));
        sub.setTextColor(0xFF9AA0AA);
        sub.setTextSize(13);

        row.addView(name);
        row.addView(sub);

        row.setOnClickListener(v -> play(entry.romId));
        row.setOnLongClickListener(v -> {
            confirmDelete(entry);
            return true;
        });
        return row;
    }

    private void play(String romId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ROM_ID, romId);
        startActivity(intent);
    }

    private void confirmDelete(RomLibrary.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.library_delete_title)
                .setMessage(getString(R.string.library_delete_message, entry.displayName))
                .setPositiveButton(R.string.library_delete_confirm, (d, w) -> {
                    try {
                        library.delete(entry.romId);
                    } catch (IOException ignored) {
                        // The files are best-effort removed; refresh reflects reality.
                    }
                    refresh();
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    private void openRomPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, OPEN_ROM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_ROM || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null || importing) {
            return;
        }
        importing = true;
        Toast.makeText(this, R.string.library_importing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                new RomImporter(this, library).importRom(uri);
                runOnUiThread(() -> {
                    importing = false;
                    refresh();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    importing = false;
                    Toast.makeText(this, R.string.library_import_failed, Toast.LENGTH_LONG).show();
                });
            }
        }, "rom-import").start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
```

- [ ] **Step 3: Reduce MainActivity to player-only (drop the picker/empty-import)**

In `MainActivity.java`, now that `LibraryActivity` owns import:
- Change the `requestRom` behavior so the LOAD chip / empty-tap RETURNS TO THE LIBRARY instead of opening a picker. Replace the `openRomPicker()` method body with:
```java
    private void openRomPicker() {
        finish(); // return to the library to choose or import a ROM
    }
```
(Keep the method name so the `EmulatorView(this, this::openRomPicker, ...)` wiring is unchanged.)
- Delete the `onActivityResult(...)` method, the `importRomAsync(...)` method, the `OPEN_ROM` and `STATE_ROM_URI` constants, the `romUri` field and its `onSaveInstanceState`/`onCreate` restore, and the `takePersistableUriPermission` logic — all of that was the old picker/import path now owned by `LibraryActivity`. Let the compiler and lint identify unused imports/fields to remove.
- If launched with no `EXTRA_ROM_ID` and no ROM (a state that should not occur once `LibraryActivity` is the launcher), `finish()` immediately: at the end of `onCreate`, add:
```java
        if (romFile == null) {
            finish();
        }
```

- [ ] **Step 4: Flip the launcher in the manifest**

In `AndroidManifest.xml`, move the `MAIN`/`LAUNCHER` intent-filter from `MainActivity` to a new `LibraryActivity`, and make `MainActivity` a non-launcher internal activity. The `<application>` activities become:
```xml
        <activity
            android:name=".LibraryActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity
            android:name=".MainActivity"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize"
            android:exported="false" />
        <activity
            android:name=".NoticesActivity"
            android:exported="false"
            android:label="@string/notices_title" />
```

- [ ] **Step 5: Build, lint, unit tests, install**

Run:
```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 0 lint errors, all unit tests pass. The app now launches into the library; importing adds a row; tapping a row plays; long-press deletes.

- [ ] **Step 6: Commit**

```sh
git add mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/LibraryActivity.java \
        mgba-android/app/src/main/java/com/trebuchetdynamics/emulator/app/MainActivity.java \
        mgba-android/app/src/main/AndroidManifest.xml \
        mgba-android/app/src/main/res/values/strings.xml
git commit -m "feat(app): library-first — LibraryActivity launcher, MainActivity plays by id

The app now opens to a list of imported ROMs (most-recently-played first) with
import and long-press delete; tapping one launches the player for that ROM. The
player drops its own picker — LOAD returns to the library.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Device verification

**Files:**
- Create: `docs/validation/phase3-rom-library-<date>.md` (use the actual execution date)

**Interfaces:**
- Consumes: everything above.
- Produces: the Phase 3 exit evidence.

- [ ] **Step 1: Install and drive the library flow**

With the device connected:
```sh
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell am start -n com.trebuchetdynamics.garnacha/com.trebuchetdynamics.emulator.app.LibraryActivity
```
Confirm, capturing screenshots:
1. **Empty state:** a fresh install (or after deleting all) shows "No games yet. Tap Import…".
2. **Import:** tap Import, pick a ROM (a `.zip` and a raw `.gba` both work); the row appears with the document's display name.
3. **Import a second ROM;** confirm the most-recently-imported/played sorts to the top.
4. **Play:** tap a row → the player opens and the game runs; press Back → returns to the library.
5. **Last-played ordering:** play the older game, Back; confirm it has moved to the top of the list.
6. **Delete:** long-press a row → confirm dialog → Delete; the row disappears and stays gone after leaving/returning.
7. **LOAD returns to library:** in the player, tap the LOAD chip → returns to the library.

- [ ] **Step 2: Confirm cascade delete and no crashes**

After deleting a ROM that had a save state, verify its private files are gone:
```sh
adb shell run-as com.trebuchetdynamics.garnacha ls files/roms files/saves files/states 2>/dev/null || echo "(run-as unavailable on benchmark build — verify via the UI: deleted game does not reappear)"
adb logcat -d | grep -icE "FATAL|ANR in com.trebuchetdynamics.garnacha"
adb exec-out screencap -p > docs/validation/phase3-library.png
```
Expected: 0 fatal/ANR; the deleted rom/save/state files are absent (or, on the non-debuggable build, the game does not reappear).

- [ ] **Step 3: Write the receipt and commit**

Create `docs/validation/phase3-rom-library-<date>.md` recording device, each of the seven observations above marked pass/fail with what was seen, the cascade-delete check, the 0-fatal/ANR result, and the screenshot. Then:
```sh
git add docs/validation/phase3-rom-library-<date>.md docs/validation/phase3-library.png
git commit -m "docs: record Phase 3 ROM library device verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** the spec's Phase 3 lists "grid/list of imported ROMs" (Task 4 list — a list, not a grid, per the plan's design note since there's no cover art), "SAF import" (Tasks 2+4), "delete" (Tasks 1 cascade + 4 UI + confirm), "last-played ordering" (Task 1 `list()` sort + Task 3 `touch` on play), and "resume" (last-played-first ordering puts the current game one tap from the top; per-ROM cartridge saves and save states already persist across sessions). No cover-art metadata (explicitly deferred).
- **Type consistency:** `RomLibrary(File)`, `Entry{romId,displayName,lastPlayedMs,romFile}`, `list()/record(String,String,long)/touch(String,long)/exists(String)/delete(String)`, `RomImporter(Context,RomLibrary)`, `RomImporter.Result{romId,romFile,displayName}`, `importRom(Uri)`, and `MainActivity.EXTRA_ROM_ID` are used identically across Tasks 1–4. MainActivity's success callback field rename (`ImportedRom.file/.id` → `RomImporter.Result.romFile/.romId`) is applied in Task 2.
- **Interlock:** Tasks 1–3 each leave the app fully building and runnable (RomLibrary is standalone; RomImporter is a behavior-preserving extraction; the id-play path is additive). Task 4 is the atomic flip to library-first and is the one integration point — verified on device in Task 5.
- **Deliberately deferred:** cover art / thumbnails, search/sort options, folders, multi-select, rename, and any settings (Phase 4) are out of scope.
- **Risk:** the delete `AlertDialog` is a standard native dialog (not a browser dialog) and is safe. The library list is rebuilt wholesale on each `onResume`/refresh — fine for the modest ROM counts a user will have; a `RecyclerView` would be premature.
