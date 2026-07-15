package com.trebuchetdynamics.emulator.app;

/**
 * Pure "feel" math for the play view: integer game scaling, idle-fade alpha, and
 * new-press detection. No android.* imports so it unit-tests on the JVM.
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
     * Alpha (0..255) for the on-screen controls: {@code maxAlpha} for {@code
     * holdMs} after the last input, then a linear fade to {@code minAlpha} over
     * {@code fadeMs}.
     */
    static int controlAlpha(long nowMs, long lastInputMs, int holdMs, int fadeMs,
                            int minAlpha, int maxAlpha) {
        long elapsed = nowMs - lastInputMs;
        if (elapsed <= holdMs) {
            return maxAlpha;
        }
        if (elapsed >= (long) holdMs + fadeMs) {
            return minAlpha;
        }
        float frac = (float) (elapsed - holdMs) / (float) fadeMs;
        return Math.round(maxAlpha - frac * (maxAlpha - minAlpha));
    }

    /** True iff {@code currentKeys} presses a key bit not already down in {@code previousKeys}. */
    static boolean introducesNewPress(int previousKeys, int currentKeys) {
        return (currentKeys & ~previousKeys) != 0;
    }
}
