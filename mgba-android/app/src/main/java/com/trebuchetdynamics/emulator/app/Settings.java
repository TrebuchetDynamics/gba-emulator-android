package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed wrapper over the app's preferences, with pure clamp/enum helpers. */
final class Settings {
    enum Orientation { AUTO, PORTRAIT, LANDSCAPE }

    enum ScaleMode { INTEGER, FILL }

    private static final String FILE = "garnacha_settings";
    private static final String K_ORIENTATION = "orientation";
    private static final String K_SCALE = "scaleMode";
    private static final String K_AUDIO_ON = "audioEnabled";
    private static final String K_VOLUME = "audioVolumePercent";
    private static final String K_HAPTICS = "haptics";
    private static final String K_OPACITY = "controlOpacityPercent";
    private static final String K_FF_SPEED = "fastForwardSpeed";

    private final SharedPreferences prefs;

    Settings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    Orientation orientation() {
        return orientationFromOrdinal(prefs.getInt(K_ORIENTATION, Orientation.AUTO.ordinal()));
    }

    void setOrientation(Orientation value) {
        prefs.edit().putInt(K_ORIENTATION, value.ordinal()).apply();
    }

    ScaleMode scaleMode() {
        return scaleModeFromOrdinal(prefs.getInt(K_SCALE, ScaleMode.INTEGER.ordinal()));
    }

    void setScaleMode(ScaleMode value) {
        prefs.edit().putInt(K_SCALE, value.ordinal()).apply();
    }

    boolean audioEnabled() {
        return prefs.getBoolean(K_AUDIO_ON, true);
    }

    void setAudioEnabled(boolean value) {
        prefs.edit().putBoolean(K_AUDIO_ON, value).apply();
    }

    int audioVolumePercent() {
        return clampPercent(prefs.getInt(K_VOLUME, 100));
    }

    void setAudioVolumePercent(int value) {
        prefs.edit().putInt(K_VOLUME, clampPercent(value)).apply();
    }

    boolean haptics() {
        return prefs.getBoolean(K_HAPTICS, true);
    }

    void setHaptics(boolean value) {
        prefs.edit().putBoolean(K_HAPTICS, value).apply();
    }

    int controlOpacityPercent() {
        return Math.max(10, clampPercent(prefs.getInt(K_OPACITY, 24)));
    }

    void setControlOpacityPercent(int value) {
        prefs.edit().putInt(K_OPACITY, Math.max(10, clampPercent(value))).apply();
    }

    int fastForwardSpeed() {
        return clampFastForwardSpeed(prefs.getInt(K_FF_SPEED, 4));
    }

    void setFastForwardSpeed(int value) {
        prefs.edit().putInt(K_FF_SPEED, clampFastForwardSpeed(value)).apply();
    }

    static int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 100);
    }

    static int clampFastForwardSpeed(int value) {
        if (value < 2) {
            return 2;
        }
        return Math.min(value, 4);
    }

    static int opacityPercentToAlpha(int percent) {
        return Math.round(clampPercent(percent) / 100f * 255f);
    }

    private static Orientation orientationFromOrdinal(int ordinal) {
        Orientation[] values = Orientation.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Orientation.AUTO;
    }

    private static ScaleMode scaleModeFromOrdinal(int ordinal) {
        ScaleMode[] values = ScaleMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ScaleMode.INTEGER;
    }
}
