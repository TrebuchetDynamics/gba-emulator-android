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
