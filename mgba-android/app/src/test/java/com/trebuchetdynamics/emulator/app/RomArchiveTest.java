package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class RomArchiveTest {

    private static final long CAP = 1024 * 1024;

    @Test
    public void rawRomStreamsThroughUnchanged() throws IOException {
        byte[] rom = romBytes(4096);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RomArchive.extractRom(new ByteArrayInputStream(rom), out, CAP);

        assertArrayEquals(rom, out.toByteArray());
    }

    @Test
    public void zipContainingOneRomIsExtracted() throws IOException {
        byte[] rom = romBytes(4096);
        byte[] zip = zipOf(entries("game.gba", rom));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RomArchive.extractRom(new ByteArrayInputStream(zip), out, CAP);

        assertArrayEquals("extracted bytes must be the ROM, not the archive",
                rom, out.toByteArray());
    }

    @Test
    public void hashIsOfTheRomNotTheArchive() throws IOException {
        // Load-bearing: the same game imported zipped or raw must resolve to the
        // same SHA-256 so MainActivity names them onto the same private file and
        // they share one save. Hashing the archive would silently split saves.
        byte[] rom = romBytes(4096);
        byte[] zip = zipOf(entries("game.gba", rom));

        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        byte[] rawHash = RomArchive.extractRom(new ByteArrayInputStream(rom), rawOut, CAP);

        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        byte[] zipHash = RomArchive.extractRom(new ByteArrayInputStream(zip), zipOut, CAP);

        assertArrayEquals("hash must be over the ROM bytes, not the archive bytes, so a raw "
                        + "and zipped import of the same game share one save file",
                rawHash, zipHash);
    }

    @Test
    public void zipWithNoGbaEntryIsRejected() {
        byte[] zip = zipOf(entries("readme.txt", romBytes(64)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IOException thrown = assertThrows(IOException.class,
                () -> RomArchive.extractRom(new ByteArrayInputStream(zip), out, CAP));

        assertEquals("Archive contains no .gba ROM", thrown.getMessage());
    }

    @Test
    public void zipWithTwoGbaEntriesIsRejected() {
        byte[] first = fill(64, (byte) 0xAA);
        byte[] second = fill(64, (byte) 0xBB);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("game1.gba", first);
        entries.put("game2.gba", second);
        byte[] zip = zipOf(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IOException thrown = assertThrows(IOException.class,
                () -> RomArchive.extractRom(new ByteArrayInputStream(zip), out, CAP));

        assertEquals("Archive contains multiple ROMs — extract the one you want", thrown.getMessage());
        assertTrue("out must not contain any bytes from the rejected second ROM "
                        + "(the caller must not mistake it for a complete, valid import)",
                !contains(out.toByteArray(), second));
    }

    @Test
    public void zipEntryExceedingTheCapIsRejected() {
        // Zip bomb: highly compressible (all zero) so the compressed archive is
        // tiny while the decompressed content blows past the cap. Proves the cap
        // is enforced against actual bytes written, not the attacker-controlled
        // (and possibly -1) ZipEntry.getSize().
        byte[] hugeButCompressible = new byte[2 * 1024 * 1024];
        byte[] zip = zipOf(entries("game.gba", hugeButCompressible));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long tinyCap = 64 * 1024;

        assertThrows(IOException.class,
                () -> RomArchive.extractRom(new ByteArrayInputStream(zip), out, tinyCap));
    }

    @Test
    public void rawRomExceedingTheCapIsRejected() {
        byte[] rom = romBytes(1024);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(IOException.class,
                () -> RomArchive.extractRom(new ByteArrayInputStream(rom), out, 16));
    }

    @Test
    public void emptyInputIsRejected() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IOException thrown = assertThrows(IOException.class,
                () -> RomArchive.extractRom(new ByteArrayInputStream(new byte[0]), out, CAP));

        assertEquals("ROM is empty", thrown.getMessage());
    }

    @Test
    public void uppercaseGbaExtensionIsAccepted() throws IOException {
        byte[] rom = romBytes(4096);
        byte[] zip = zipOf(entries("GAME.GBA", rom));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RomArchive.extractRom(new ByteArrayInputStream(zip), out, CAP);

        assertArrayEquals(rom, out.toByteArray());
    }

    @Test
    public void nonRomEntriesAreIgnored() throws IOException {
        byte[] rom = romBytes(4096);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", romBytes(32));
        entries.put("cover.png", romBytes(128));
        entries.put("game.gba", rom);
        byte[] zip = zipOf(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RomArchive.extractRom(new ByteArrayInputStream(zip), out, CAP);

        assertArrayEquals(rom, out.toByteArray());
    }

    private static Map<String, byte[]> entries(String name, byte[] content) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, content);
        return entries;
    }

    private static byte[] zipOf(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] romBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static byte[] fill(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
