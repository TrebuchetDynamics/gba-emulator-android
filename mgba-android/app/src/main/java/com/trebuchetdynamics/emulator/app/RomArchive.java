package com.trebuchetdynamics.emulator.app;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a ROM (GBA, GB, or GBC — by filename, not content, for the zip
 * path; content is validated by the caller via {@link RomSystem#detect})
 * from either a raw stream or a zip archive, streaming throughout with a
 * fixed-size buffer so the ROM is never held fully in memory. Pure java.io /
 * java.util.zip / java.security logic — no android.* imports — so it is
 * unit-testable on the JVM without Robolectric or a device, the same pattern
 * {@code ControlLayout} uses.
 */
final class RomArchive {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final String[] ROM_SUFFIXES = {".gba", ".gb", ".gbc"};

    private RomArchive() {
    }

    /**
     * SHA-256 of the ROM bytes written to {@code out}.
     *
     * <p>Note: on failure (a thrown {@link IOException}) {@code out} may already
     * hold partial bytes — e.g. a first matched {@code .gba} entry that was fully
     * streamed before a second entry triggered the duplicate-ROM rejection.
     * Callers must discard the destination on exception rather than trust it as a
     * complete import; {@code MainActivity} already does this unconditionally.
     */
    static byte[] extractRom(InputStream in, OutputStream out, long maxBytes) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, BUFFER_SIZE);
        MessageDigest digest = sha256();
        DigestOutputStream digestOut = new DigestOutputStream(out, digest);
        long total = looksLikeZip(buffered)
                ? extractFromZip(buffered, digestOut, maxBytes)
                : copyCapped(buffered, digestOut, maxBytes);
        if (total == 0) {
            throw new IOException("ROM is empty");
        }
        return digest.digest();
    }

    private static boolean looksLikeZip(BufferedInputStream buffered) throws IOException {
        buffered.mark(ZIP_MAGIC.length);
        byte[] header = new byte[ZIP_MAGIC.length];
        int read = readFully(buffered, header);
        buffered.reset();
        if (read < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; ++i) {
            if (header[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = in.read(buffer, total, buffer.length - total);
            if (count == -1) {
                break;
            }
            total += count;
        }
        return total;
    }

    // Single forward pass: the first .gba entry found is streamed straight to
    // `out` as soon as it is identified (a ZipInputStream cannot be rewound).
    // The remaining entries are then walked by name only — no data is read
    // for them — so a rejected duplicate never contributes any of its own
    // bytes to `out`; only if a second .gba entry name turns up do we abort.
    private static long extractFromZip(InputStream in, OutputStream out, long maxBytes) throws IOException {
        long total = -1;
        // `in` is borrowed from extractRom's caller, which owns closing it. Wrap it
        // in a non-closing shim before handing it to ZipInputStream's
        // try-with-resources, so the inflater is still released deterministically
        // without ZipInputStream#close() cascading a second close onto the caller's
        // stream (see the raw path below, which never closes `in` either).
        try (ZipInputStream zis = new ZipInputStream(new NonClosingInputStream(in))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !isRomEntry(entry.getName())) {
                    continue;
                }
                if (total >= 0) {
                    throw new IOException("Archive contains multiple ROMs — extract the one you want");
                }
                total = copyCapped(zis, out, maxBytes);
            }
        }
        if (total < 0) {
            throw new IOException("Archive contains no ROM (.gba/.gb/.gbc)");
        }
        return total;
    }

    /** Blocks {@link #close()} from cascading onto a borrowed, caller-owned stream. */
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // No-op: `in` belongs to extractRom's caller, not to this stream.
        }
    }

    private static boolean isRomEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : ROM_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    // Enforces the cap against bytes actually written (i.e. the decompressed
    // size for a zip entry), never trusting ZipEntry.getSize() — which is
    // attacker-controlled and may be -1 — so a zip bomb is harmless.
    private static long copyCapped(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IOException("ROM exceeds the GBA cartridge limit");
            }
            out.write(buffer, 0, count);
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
