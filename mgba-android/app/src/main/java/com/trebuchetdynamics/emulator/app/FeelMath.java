package com.trebuchetdynamics.emulator.app;

/**
 * Pure "feel" math for the play view: game scaling, control visibility, and
 * input handling. No android.* imports so it unit-tests on the JVM.
 */
final class FeelMath {
    private FeelMath() {
    }

    /** Immutable rectangle in view pixels. */
    static final class Box {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /**
     * The largest integer multiple of {@code srcW x srcH} that fits inside the
     * given box, centred. Never smaller than 1x (so a box smaller than native
     * still draws at native size, which our layout never produces in practice).
     */
    static Box integerScale(float boxLeft, float boxTop, float boxRight, float boxBottom,
                            int srcW, int srcH) {
        float boxW = boxRight - boxLeft;
        float boxH = boxBottom - boxTop;
        int scale = (int) Math.floor(Math.min(boxW / srcW, boxH / srcH));
        if (scale < 1) {
            scale = 1;
        }
        float drawW = (float) scale * srcW;
        float drawH = (float) scale * srcH;
        float left = boxLeft + (boxW - drawW) / 2f;
        float top = boxTop + (boxH - drawH) / 2f;
        return new Box(left, top, left + drawW, top + drawH);
    }

    /**
     * The largest rectangle that preserves {@code srcW:srcH} aspect and fits
     * inside the box, centred. Unlike {@link #integerScale} the scale is not
     * floored to an integer, so it fills the box more fully at the cost of
     * uneven pixel sizes.
     */
    static Box fitScale(float boxLeft, float boxTop, float boxRight, float boxBottom,
                        int srcW, int srcH) {
        float boxW = boxRight - boxLeft;
        float boxH = boxBottom - boxTop;
        float scale = Math.min(boxW / srcW, boxH / srcH);
        float drawW = scale * srcW;
        float drawH = scale * srcH;
        float left = boxLeft + (boxW - drawW) / 2f;
        float top = boxTop + (boxH - drawH) / 2f;
        return new Box(left, top, left + drawW, top + drawH);
    }

    /** Alpha (0..255) for controls, unchanged by input state. */
    static int controlAlpha(int configuredAlpha, int capAlpha) {
        return Math.min(configuredAlpha, capAlpha);
    }

    /** True when optional touch controls have been idle for the configured delay. */
    static boolean shouldAutoHideControls(boolean enabled, boolean touchActive,
            long nowMs, long lastTouchMs, long delayMs) {
        return enabled && !touchActive && nowMs - lastTouchMs >= delayMs;
    }

    /** True iff {@code currentKeys} presses a key bit not already down in {@code previousKeys}. */
    static boolean introducesNewPress(int previousKeys, int currentKeys) {
        return (currentKeys & ~previousKeys) != 0;
    }

    /** Fixed 15 Hz whole-mask pulse at the emulator's nominal 60 FPS. */
    static int applyTurbo(int normalKeys, int turboKeys, long frameIndex) {
        return frameIndex % 4 < 2 ? normalKeys | turboKeys : normalKeys;
    }
}
