package com.trebuchetdynamics.emulator.mgba;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

@RunWith(AndroidJUnit4.class)
public class MgbaCoreInstrumentedTest {
    @Test
    public void pinnedCoreLoadsAndInitializesOnAndroid() {
        assertEquals("0.10.5", MgbaCore.version());
        assertTrue(MgbaCore.canCreateGbaCore());
    }

    @Test
    public void canCreateGbCore() {
        assertTrue(MgbaCore.canCreateGbCore());
    }

    @Test
    public void sessionRunsMitLicensedRomAndRestoresState() throws Exception {
        byte[] rom = readAsset("hello.gba");
        int[] pixels = new int[MgbaSession.FRAME_PIXELS];
        short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];

        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(rom);
            int totalAudioFrames = 0;
            for (int i = 0; i < 10; ++i) {
                totalAudioFrames += session.runFrame(0, pixels, audio);
            }

            assertEquals(10, session.frameCounter());
            assertTrue("Expected generated audio frames", totalAudioFrames > 0);
            int nonBlackPixels = 0;
            for (int pixel : pixels) {
                if (pixel != 0xFF000000) {
                    ++nonBlackPixels;
                }
            }
            assertTrue("Expected rendered test-ROM pixels", nonBlackPixels > 0);

            byte[] state = session.saveState();
            assertNotNull(state);
            assertTrue(state.length > 0);
            long savedFrame = session.frameCounter();
            session.runFrame(MgbaSession.KEY_A, pixels, audio);
            assertTrue(session.frameCounter() > savedFrame);
            session.loadState(state);
            assertEquals(savedFrame, session.frameCounter());
            assertNotNull(session.copySavedata());
        }
    }

    @Test
    public void sessionRunsMitLicensedRomFromPrivateFile() throws Exception {
        File rom = copyAssetToCache("hello.gba");
        int[] pixels = new int[MgbaSession.FRAME_PIXELS];
        short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];

        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(rom);
            session.runFrame(0, pixels, audio);
            assertEquals(1, session.frameCounter());
        } finally {
            assertTrue(rom.delete() || !rom.exists());
        }
    }

    @Test
    public void cartridgeSavedataRoundTripsBetweenSessions() throws Exception {
        byte[] rom = readAsset("sram.gba");
        int[] pixels = new int[MgbaSession.FRAME_PIXELS];
        short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
        byte[] savedata;

        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(rom);
            for (int i = 0; i < 10; ++i) {
                session.runFrame(0, pixels, audio);
            }
            savedata = session.copySavedata();
            assertTrue("Expected detected SRAM", savedata.length > 0);
        }

        try (MgbaSession restored = new MgbaSession()) {
            restored.loadRom(rom);
            restored.restoreSavedata(savedata);
            assertArrayEquals(savedata, restored.copySavedata());
        }
    }

    @Test
    public void closedSessionRejectsFurtherUse() {
        MgbaSession session = new MgbaSession();
        session.close();
        try {
            session.loadRom(new byte[] {1});
            fail("Closed session accepted ROM data");
        } catch (IllegalStateException expected) {
            // Expected product-facing lifecycle behavior.
        }
    }

    @Test
    public void resetReturnsTheCoreToPowerOn() throws Exception {
        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(readAsset("hello.gba"));
            int[] pixels = new int[MgbaSession.FRAME_PIXELS];
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
            for (int i = 0; i < 30; i++) {
                session.runFrame(0, pixels, audio);
            }
            long before = session.frameCounter();
            assertTrue("frames should have advanced", before >= 30);
            session.reset();
            assertTrue("reset should return the frame counter toward power-on",
                    session.frameCounter() < before);
        }
    }

    @Test
    public void dmgPaletteAppliesToGameBoySessionWithoutBreakingIt() throws Exception {
        try (MgbaSession session = new MgbaSession(MgbaSession.PLATFORM_GB)) {
            session.loadRom(readAsset("hello.gb"));
            // Apply a DMG palette right after load, exactly as the player does.
            // This exercises the nativeSetDmgPalette JNI signature via real
            // linkage — a Java/native signature mismatch throws UnsatisfiedLinkError.
            session.setDmgPalette(new int[] { 0xFFFFFFFF, 0xFFA9A9A9, 0xFF545454, 0xFF000000 });

            int[] pixels = new int[session.framePixels()];
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
            for (int i = 0; i < 3; i++) {
                session.runFrame(0, pixels, audio);
            }
            assertEquals(3, session.frameCounter());
        }
    }

    private static byte[] readAsset(String name) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static File copyAssetToCache(String name) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        File destination = new File(context.getCacheDir(), "mgba-path-" + name);
        try (InputStream input = context.getAssets().open(name);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        return destination;
    }
}
