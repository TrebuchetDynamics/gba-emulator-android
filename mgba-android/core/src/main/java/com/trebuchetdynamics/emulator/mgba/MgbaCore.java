package com.trebuchetdynamics.emulator.mgba;

/** Minimal, product-owned boundary around the MPL-2.0 mGBA core. */
public final class MgbaCore {
    static {
        System.loadLibrary("mgba-android");
    }

    private MgbaCore() {}

    /** Returns the pinned upstream mGBA version compiled into this library. */
    public static native String version();

    /** Creates, initializes, and destroys a GBA core as a native integration smoke test. */
    public static native boolean canCreateGbaCore();
}
