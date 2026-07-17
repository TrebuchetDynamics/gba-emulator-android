package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

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

        RomSystem system = RomSystem.detect(readHeader(temporary));
        if (system == RomSystem.UNKNOWN) {
            temporary.delete();
            throw new IOException("Not a valid ROM");
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
        library.record(romId, displayName, system, System.currentTimeMillis());
        return new Result(romId, destination, displayName);
    }

    /** Reads just enough of the front of {@code file} for {@link RomSystem#detect}. */
    private static byte[] readHeader(File file) throws IOException {
        byte[] buffer = new byte[0x150];
        try (FileInputStream in = new FileInputStream(file)) {
            int total = 0;
            int count;
            while (total < buffer.length && (count = in.read(buffer, total, buffer.length - total)) != -1) {
                total += count;
            }
            return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
        }
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
        // Strip a trailing .gba / .gbc / .gb / .zip for a cleaner title.
        String lower = name.toLowerCase();
        if (lower.endsWith(".gba") || lower.endsWith(".gbc") || lower.endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        } else if (lower.endsWith(".gb")) {
            name = name.substring(0, name.length() - 3);
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
