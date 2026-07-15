package com.trebuchetdynamics.emulator.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Per-ROM save-state slots on disk. Pure java.io so it unit-tests on the JVM.
 * Each ROM gets a directory under {@code statesRoot}; each slot is one file,
 * written atomically (temp file + rename).
 */
final class SaveStateStore {
    static final int SLOT_COUNT = 4;
    private static final long MAX_STATE_BYTES = 16L * 1024 * 1024;

    private final File romDir;

    SaveStateStore(File statesRoot, String romId) {
        if (romId == null || !romId.matches("[A-Za-z0-9._-]+")
                || romId.equals(".") || romId.equals("..")) {
            throw new IllegalArgumentException("Unsafe ROM id");
        }
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
        long length = file.length();
        if (length <= 0 || length > MAX_STATE_BYTES) {
            throw new IOException("Save state is missing, empty, or too large");
        }
        byte[] data = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int count = in.read(data, offset, data.length - offset);
                if (count < 0) {
                    throw new IOException("Truncated save state");
                }
                offset += count;
            }
        }
        return data;
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
