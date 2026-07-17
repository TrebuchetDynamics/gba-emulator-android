package com.trebuchetdynamics.emulator.app;

/** Pure conversions for the layout editor's gestures and scale slider. */
final class LayoutEditMath {
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2f;

    private LayoutEditMath() {
    }

    /** A finger x/y as a normalized fraction of the extent, clamped to [0,1]. */
    static float toNorm(float pixel, float extent) {
        if (extent <= 0f) {
            return 0f;
        }
        return ControlOverrides.clampNorm(pixel / extent);
    }

    /** Slider position 0..max mapped linearly to [0.5, 2.0]. */
    static float scaleForProgress(int progress, int max) {
        if (max <= 0) {
            return 1f;
        }
        float t = (float) progress / max;
        return MIN_SCALE + t * (MAX_SCALE - MIN_SCALE);
    }

    /** Inverse of {@link #scaleForProgress}, rounded to the nearest step. */
    static int progressForScale(float scale, int max) {
        float clamped = ControlOverrides.clampScale(scale);
        float t = (clamped - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
        return Math.round(t * max);
    }
}
