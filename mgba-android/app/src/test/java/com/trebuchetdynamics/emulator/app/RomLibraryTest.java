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
        lib.record("aaa1", "Zelda", RomSystem.GBA, 5000L);
        List<RomLibrary.Entry> entries = lib.list();
        assertEquals(1, entries.size());
        assertEquals("aaa1", entries.get(0).romId);
        assertEquals("Zelda", entries.get(0).displayName);
        assertEquals(5000L, entries.get(0).lastPlayedMs);
        assertTrue(entries.get(0).romFile.isFile());
        assertEquals(RomSystem.GBA, entries.get(0).system);
    }

    @Test
    public void recordedSystemRoundTripsThroughList() throws IOException {
        writeRom("gbc1");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("gbc1", "Pokemon Gold", RomSystem.GBC, 1000L);
        List<RomLibrary.Entry> entries = lib.list();
        assertEquals(1, entries.size());
        assertEquals(RomSystem.GBC, entries.get(0).system);
    }

    @Test
    public void missingStoredSystemDefaultsToGba() throws IOException {
        // Simulates a pre-existing entry recorded before system tracking existed:
        // name + played are present, but no ".system" key.
        writeRom("legacy");
        File roms = new File(filesDir, "roms");
        roms.mkdirs();
        java.util.Properties p = new java.util.Properties();
        p.setProperty("legacy.name", "Legacy Game");
        p.setProperty("legacy.played", "1000");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new File(filesDir, "library.properties"))) {
            p.store(out, null);
        }
        List<RomLibrary.Entry> entries = new RomLibrary(filesDir).list();
        assertEquals(1, entries.size());
        assertEquals(RomSystem.GBA, entries.get(0).system);
    }

    @Test
    public void listSortsMostRecentlyPlayedFirst() throws IOException {
        writeRom("older");
        writeRom("newer");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("older", "Older", RomSystem.GBA, 1000L);
        lib.record("newer", "Newer", RomSystem.GBA, 2000L);
        List<RomLibrary.Entry> entries = lib.list();
        assertEquals("newer", entries.get(0).romId);
        assertEquals("older", entries.get(1).romId);
    }

    @Test
    public void touchBumpsOrder() throws IOException {
        writeRom("a");
        writeRom("b");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("a", "A", RomSystem.GBA, 1000L);
        lib.record("b", "B", RomSystem.GBA, 2000L);
        lib.touch("a", 3000L); // a now newest
        assertEquals("a", lib.list().get(0).romId);
    }

    @Test
    public void touchPreservesRecordedSystem() throws IOException {
        writeRom("gb1");
        RomLibrary lib = new RomLibrary(filesDir);
        lib.record("gb1", "Kirby", RomSystem.GB, 1000L);
        lib.touch("gb1", 2000L);
        List<RomLibrary.Entry> entries = lib.list();
        assertEquals(1, entries.size());
        assertEquals(RomSystem.GB, entries.get(0).system);
        assertEquals(2000L, entries.get(0).lastPlayedMs);
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
        lib.record("victim", "Victim", RomSystem.GBA, 1000L);
        assertTrue(lib.exists("victim"));

        lib.delete("victim");

        assertFalse(lib.exists("victim"));
        assertFalse(new File(saves, "victim.sav").exists());
        assertFalse(states.exists());
        assertTrue(lib.list().isEmpty()); // index entry gone too
    }

    @Test
    public void corruptIndexDoesNotBreakEnumeration() throws IOException {
        writeRom("good");
        Files.write(new File(filesDir, "library.properties").toPath(),
                "bad=\\uZZZZ\n".getBytes());
        List<RomLibrary.Entry> entries = new RomLibrary(filesDir).list();
        assertEquals(1, entries.size());
        assertEquals("good", entries.get(0).romId);
    }

    @Test
    public void concurrentRecordsDoNotLoseEntries() throws Exception {
        final RomLibrary lib = new RomLibrary(filesDir);
        int n = 20;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    writeRom("rom" + idx);
                    lib.record("rom" + idx, "Name" + idx, RomSystem.GBA, idx);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        List<RomLibrary.Entry> entries = lib.list();
        assertEquals(n, entries.size());
        for (int i = 0; i < n; i++) {
            boolean found = false;
            for (RomLibrary.Entry e : entries) {
                if (("rom" + i).equals(e.romId)) {
                    found = true;
                    break;
                }
            }
            assertTrue("missing rom" + i, found);
        }
    }
}
