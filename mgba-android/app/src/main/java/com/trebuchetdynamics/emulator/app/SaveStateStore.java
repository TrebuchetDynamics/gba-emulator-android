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
    static final int AUTO_SLOT_COUNT = 3;
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
        writeAtomic(slotFile(slot), new File(romDir, "slot" + slot + ".tmp"), state);
    }

    byte[] read(int slot) throws IOException {
        requireSlot(slot);
        return readFile(slotFile(slot));
    }

    /** Atomically stores a new autosave while retaining the previous two. */
    void writeAuto(byte[] state) throws IOException {
        requireDirectory();
        File temp = new File(romDir, "auto.tmp");
        writeTemp(temp, state);
        File oldest = autoFile(AUTO_SLOT_COUNT);
        oldest.delete();
        for (int i = AUTO_SLOT_COUNT - 1; i >= 1; i--) {
            File from = autoFile(i);
            if (from.isFile() && !from.renameTo(autoFile(i + 1))) {
                temp.delete();
                throw new IOException("Could not rotate autosaves");
            }
        }
        if (!temp.renameTo(autoFile(1))) {
            temp.delete();
            throw new IOException("Could not commit autosave");
        }
    }

    byte[] readAuto(int generation) throws IOException {
        requireAutoGeneration(generation);
        return readFile(autoFile(generation));
    }

    boolean autoExists(int generation) {
        requireAutoGeneration(generation);
        return autoFile(generation).isFile();
    }

    long autoTimestamp(int generation) {
        requireAutoGeneration(generation);
        File file = autoFile(generation);
        return file.isFile() ? file.lastModified() : 0L;
    }

    byte[] readLatestAuto() throws IOException {
        for (int i = 1; i <= AUTO_SLOT_COUNT; i++) {
            if (autoExists(i)) {
                return readAuto(i);
            }
        }
        return null;
    }

    boolean hasAutoState() {
        for (int i = 1; i <= AUTO_SLOT_COUNT; i++) {
            if (autoExists(i)) {
                return true;
            }
        }
        return false;
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

    /** Returns the newest occupied slot, or 0 when this game has no states. */
    int newestSlot() {
        int newest = 0;
        long newestTime = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            long time = timestamp(slot);
            if (time > 0 && time >= newestTime) {
                newest = slot;
                newestTime = time;
            }
        }
        return newest;
    }

    private void writeAtomic(File target, File temp, byte[] state) throws IOException {
        requireDirectory();
        writeTemp(temp, state);
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not commit the save state");
        }
    }

    private void writeTemp(File temp, byte[] state) throws IOException {
        if (state == null || state.length == 0) {
            throw new IllegalArgumentException("Refusing to write an empty state");
        }
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(state);
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            temp.delete();
            throw e;
        }
    }

    private byte[] readFile(File file) throws IOException {
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

    private void requireDirectory() throws IOException {
        if (!romDir.isDirectory() && !romDir.mkdirs()) {
            throw new IOException("Could not create the save-state directory");
        }
    }

    private File slotFile(int slot) {
        return new File(romDir, "slot" + slot + ".state");
    }

    private File autoFile(int index) {
        return new File(romDir, "auto" + index + ".state");
    }

    private static void requireSlot(int slot) {
        if (slot < 1 || slot > SLOT_COUNT) {
            throw new IllegalArgumentException("Slot must be 1.." + SLOT_COUNT);
        }
    }

    private static void requireAutoGeneration(int generation) {
        if (generation < 1 || generation > AUTO_SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "Autosave generation must be 1.." + AUTO_SLOT_COUNT);
        }
    }
}
