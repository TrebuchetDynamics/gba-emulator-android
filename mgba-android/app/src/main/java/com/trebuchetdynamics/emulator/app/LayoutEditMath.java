package com.trebuchetdynamics.emulator.app;

/** Pure conversions for the layout editor's gestures and size actions. */
final class LayoutEditMath {
    private LayoutEditMath() {
    }

    /** A finger x/y as a normalized fraction of the extent, clamped to [0,1]. */
    static float toNorm(float pixel, float extent) {
        if (extent <= 0f) {
            return 0f;
        }
        return ControlOverrides.clampNorm(pixel / extent);
    }

    /** Move one 10% size step in either direction, clamped to supported limits. */
    static float stepScale(float current, int direction) {
        return ControlOverrides.clampScale(current + (direction < 0 ? -0.1f : 0.1f));
    }
}
