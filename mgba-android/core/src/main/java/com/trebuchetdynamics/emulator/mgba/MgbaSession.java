package com.trebuchetdynamics.emulator.mgba;

import java.io.File;

/**
 * Product-owned, single-threaded session boundary around an mGBA GBA core.
 *
 * <p>Callers provide ROM bytes obtained through their own Android storage layer. No ROM or BIOS
 * content is bundled by this library.</p>
 */
public final class MgbaSession implements AutoCloseable {
    public static final int VIDEO_WIDTH = 240;
    public static final int VIDEO_HEIGHT = 160;
    public static final int FRAME_PIXELS = VIDEO_WIDTH * VIDEO_HEIGHT;
    public static final int AUDIO_SAMPLE_RATE = 48_000;
    public static final int MIN_AUDIO_BUFFER_SAMPLES = 2_048;
    private static final long MAX_GBA_ROM_BYTES = 32L * 1024 * 1024;

    public static final int PLATFORM_GBA = 0;
    public static final int PLATFORM_GB = 1;

    public static final int KEY_A = 1 << 0;
    public static final int KEY_B = 1 << 1;
    public static final int KEY_SELECT = 1 << 2;
    public static final int KEY_START = 1 << 3;
    public static final int KEY_RIGHT = 1 << 4;
    public static final int KEY_LEFT = 1 << 5;
    public static final int KEY_UP = 1 << 6;
    public static final int KEY_DOWN = 1 << 7;
    public static final int KEY_R = 1 << 8;
    public static final int KEY_L = 1 << 9;

    static {
        System.loadLibrary("mgba-android");
    }

    private long handle;
    private boolean loaded;
    private final int videoWidth;
    private final int videoHeight;

    public MgbaSession() {
        this(PLATFORM_GBA);
    }

    public MgbaSession(int platform) {
        handle = nativeCreate(platform);
        if (handle == 0) {
            throw new IllegalStateException("Could not initialize the mGBA core");
        }
        videoWidth = nativeVideoWidth(handle);
        videoHeight = nativeVideoHeight(handle);
    }

    public int videoWidth() {
        return videoWidth;
    }

    public int videoHeight() {
        return videoHeight;
    }

    public int framePixels() {
        return videoWidth * videoHeight;
    }

    /** Loads one GBA ROM into this session. A session cannot be reused for another ROM. */
    public synchronized void loadRom(byte[] rom) {
        requireOpen();
        if (loaded) {
            throw new IllegalStateException("A ROM is already loaded");
        }
        if (rom == null || rom.length == 0) {
            throw new IllegalArgumentException("ROM data is empty");
        }
        if (!nativeLoadRom(handle, rom)) {
            throw new IllegalArgumentException("mGBA rejected the ROM data");
        }
        loaded = true;
    }

    /** Loads one GBA ROM from a private, seekable file without copying it into the Java heap. */
    public synchronized void loadRom(File rom) {
        requireOpen();
        if (loaded) {
            throw new IllegalStateException("A ROM is already loaded");
        }
        if (rom == null || !rom.isFile() || rom.length() <= 0 || rom.length() > MAX_GBA_ROM_BYTES) {
            throw new IllegalArgumentException("ROM file is missing, empty, or too large");
        }
        if (!nativeLoadRomFile(handle, rom.getAbsolutePath())) {
            throw new IllegalArgumentException("mGBA rejected the ROM file");
        }
        loaded = true;
    }

    /**
     * Runs one emulated frame.
     *
     * @param keys bitwise combination of {@code KEY_*} constants
     * @param argbPixels output buffer containing 240×160 Android ARGB pixels
     * @param stereoAudio output buffer containing interleaved signed 16-bit stereo samples
     * @return number of stereo audio frames written
     */
    public synchronized int runFrame(int keys, int[] argbPixels, short[] stereoAudio) {
        requireLoaded();
        if (argbPixels == null || argbPixels.length < FRAME_PIXELS) {
            throw new IllegalArgumentException("Pixel buffer must hold 240x160 pixels");
        }
        if (stereoAudio == null || stereoAudio.length < MIN_AUDIO_BUFFER_SAMPLES) {
            throw new IllegalArgumentException("Audio buffer is too small");
        }
        return nativeRunFrame(handle, keys & 0x3FF, argbPixels, stereoAudio);
    }

    public synchronized long frameCounter() {
        requireLoaded();
        return nativeFrameCounter(handle);
    }

    public synchronized byte[] saveState() {
        requireLoaded();
        byte[] state = nativeSaveState(handle);
        if (state == null) {
            throw new IllegalStateException("Could not serialize mGBA state");
        }
        return state;
    }

    public synchronized void loadState(byte[] state) {
        requireLoaded();
        if (state == null || state.length == 0 || !nativeLoadState(handle, state)) {
            throw new IllegalArgumentException("Invalid mGBA save state");
        }
    }

    /** Soft-resets the loaded core to its power-on state. Cartridge save data persists. */
    public synchronized void reset() {
        requireLoaded();
        if (!nativeReset(handle)) {
            throw new IllegalStateException("mGBA could not reset the core");
        }
    }

    /** Returns the current cartridge save data, or an empty array when the ROM has none. */
    public synchronized byte[] copySavedata() {
        requireLoaded();
        byte[] data = nativeCopySavedata(handle);
        return data == null ? new byte[0] : data;
    }

    public synchronized void restoreSavedata(byte[] data) {
        requireLoaded();
        if (data == null || !nativeRestoreSavedata(handle, data)) {
            throw new IllegalArgumentException("Invalid cartridge save data");
        }
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
            loaded = false;
        }
    }

    private void requireOpen() {
        if (handle == 0) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private void requireLoaded() {
        requireOpen();
        if (!loaded) {
            throw new IllegalStateException("No ROM is loaded");
        }
    }

    private static native long nativeCreate(int platform);
    private static native int nativeVideoWidth(long handle);
    private static native int nativeVideoHeight(long handle);
    private static native boolean nativeLoadRom(long handle, byte[] rom);
    private static native boolean nativeLoadRomFile(long handle, String path);
    private static native int nativeRunFrame(long handle, int keys, int[] pixels, short[] audio);
    private static native long nativeFrameCounter(long handle);
    private static native byte[] nativeSaveState(long handle);
    private static native boolean nativeLoadState(long handle, byte[] state);
    private static native boolean nativeReset(long handle);
    private static native byte[] nativeCopySavedata(long handle);
    private static native boolean nativeRestoreSavedata(long handle, byte[] data);
    private static native void nativeDestroy(long handle);
}
