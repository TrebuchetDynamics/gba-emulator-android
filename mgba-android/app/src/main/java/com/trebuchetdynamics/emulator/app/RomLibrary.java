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
