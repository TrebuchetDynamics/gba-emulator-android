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
