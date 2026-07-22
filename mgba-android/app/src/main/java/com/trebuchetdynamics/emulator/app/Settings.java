package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Typed wrapper over the app's preferences, with pure clamp/enum helpers. */
final class Settings {
    // Persisted by ordinal in SharedPreferences — append new values only; never
    // reorder or remove existing ones, or stored user choices remap silently.
    enum Orientation { AUTO, PORTRAIT, LANDSCAPE }

    // Persisted by ordinal in SharedPreferences — append new values only; never
    // reorder or remove existing ones, or stored user choices remap silently.
    enum ScaleMode { INTEGER, FILL }

    // Persisted by ordinal in SharedPreferences — append new values only.
    enum TouchVisibility { ALWAYS, AFTER_10_SECONDS, WITH_GAMEPAD }

    private static final String FILE = "garnacha_settings";
    private static final String K_ORIENTATION = "orientation";
    private static final String K_SCALE = "scaleMode";
    private static final String K_SMOOTH_VIDEO = "smoothVideo";
    private static final String K_AUDIO_ON = "audioEnabled";
    private static final String K_VOLUME = "audioVolumePercent";
    private static final String K_HAPTICS = "haptics";
    private static final String K_OPACITY = "controlOpacityPercent";
    private static final String K_ACTIVE_OPACITY = "activeControlOpacityPercent";
    private static final String K_FF_SPEED = "fastForwardSpeed";
    private static final String K_FRAMESKIP = "frameskip";
    private static final String K_GAMEPAD = "gamepadBindings";
    private static final String K_LAYOUT_PORTRAIT = "layoutOverridesPortrait";
    private static final String K_LAYOUT_LANDSCAPE = "layoutOverridesLandscape";
    private static final String K_MACROS_PORTRAIT = "macroControlsPortrait";
    private static final String K_MACROS_LANDSCAPE = "macroControlsLandscape";
    private static final String K_DMG_PALETTE = "dmgPalette";
    private static final String K_AUTO_LOAD_STATE = "autoLoadState";
    private static final String K_CONFIRM_RESET = "confirmReset";
    private static final String K_TOUCH_VISIBILITY = "touchVisibility";
    private static final String K_HIDE_TOUCH_GAMEPAD = "hideTouchWithGamepad";

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

    boolean smoothVideo() {
        return prefs.getBoolean(K_SMOOTH_VIDEO, false);
    }

    void setSmoothVideo(boolean value) {
        prefs.edit().putBoolean(K_SMOOTH_VIDEO, value).apply();
    }

    /** Monochrome palette for original Game Boy (DMG) games; default green. */
    DmgPalette dmgPalette() {
        return dmgPaletteFromOrdinal(prefs.getInt(K_DMG_PALETTE, DmgPalette.DEFAULT.ordinal()));
    }

    void setDmgPalette(DmgPalette value) {
        prefs.edit().putInt(K_DMG_PALETTE, value.ordinal()).apply();
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

    int activeControlOpacityPercent() {
        return Math.max(10, clampPercent(prefs.getInt(K_ACTIVE_OPACITY, 70)));
    }

    void setActiveControlOpacityPercent(int value) {
        prefs.edit().putInt(K_ACTIVE_OPACITY, Math.max(10, clampPercent(value))).apply();
    }

    int fastForwardSpeed() {
        return clampFastForwardSpeed(prefs.getInt(K_FF_SPEED, 4));
    }

    void setFastForwardSpeed(int value) {
        prefs.edit().putInt(K_FF_SPEED, clampFastForwardSpeed(value)).apply();
    }

    int frameskip() {
        return clampFrameskip(prefs.getInt(K_FRAMESKIP, 0));
    }

    void setFrameskip(int value) {
        prefs.edit().putInt(K_FRAMESKIP, clampFrameskip(value)).apply();
    }

    boolean autoLoadState() {
        return prefs.getBoolean(K_AUTO_LOAD_STATE, true);
    }

    void setAutoLoadState(boolean value) {
        prefs.edit().putBoolean(K_AUTO_LOAD_STATE, value).apply();
    }

    boolean confirmReset() {
        return prefs.getBoolean(K_CONFIRM_RESET, true);
    }

    void setConfirmReset(boolean value) {
        prefs.edit().putBoolean(K_CONFIRM_RESET, value).apply();
    }

    TouchVisibility touchVisibility() {
        if (!prefs.contains(K_TOUCH_VISIBILITY)) {
            return prefs.getBoolean(K_HIDE_TOUCH_GAMEPAD, false)
                    ? TouchVisibility.WITH_GAMEPAD : TouchVisibility.ALWAYS;
        }
        return touchVisibilityFromOrdinal(prefs.getInt(
                K_TOUCH_VISIBILITY, TouchVisibility.ALWAYS.ordinal()));
    }

    void setTouchVisibility(TouchVisibility value) {
        prefs.edit().putInt(K_TOUCH_VISIBILITY, value.ordinal()).apply();
    }

    KeyBindings gamepadBindings(Map<Integer, Integer> defaults) {
        return KeyBindings.parse(prefs.getString(K_GAMEPAD, ""), defaults);
    }

    void setGamepadBindings(KeyBindings bindings) {
        prefs.edit().putString(K_GAMEPAD, bindings.serialize()).apply();
    }

    ControlOverrides controlOverrides(boolean landscape) {
        String stored = prefs.getString(landscape ? K_LAYOUT_LANDSCAPE : K_LAYOUT_PORTRAIT, "");
        return ControlOverrides.parse(stored);
    }

    void setControlOverrides(boolean landscape, ControlOverrides overrides) {
        prefs.edit()
                .putString(landscape ? K_LAYOUT_LANDSCAPE : K_LAYOUT_PORTRAIT, overrides.serialize())
                .apply();
    }

    MacroControls macroControls(boolean landscape) {
        return MacroControls.parse(prefs.getString(
                landscape ? K_MACROS_LANDSCAPE : K_MACROS_PORTRAIT, ""));
    }

    void setControlLayout(boolean landscape, ControlOverrides overrides,
            MacroControls macros, int opacityPercent) {
        prefs.edit()
                .putString(landscape ? K_LAYOUT_LANDSCAPE : K_LAYOUT_PORTRAIT,
                        overrides.serialize())
                .putString(landscape ? K_MACROS_LANDSCAPE : K_MACROS_PORTRAIT,
                        macros.serialize())
                .putInt(K_OPACITY, Math.max(10, clampPercent(opacityPercent)))
                .apply();
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

    static int clampFrameskip(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 3);
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

    private static TouchVisibility touchVisibilityFromOrdinal(int ordinal) {
        TouchVisibility[] values = TouchVisibility.values();
        return ordinal >= 0 && ordinal < values.length
                ? values[ordinal] : TouchVisibility.ALWAYS;
    }

    private static DmgPalette dmgPaletteFromOrdinal(int ordinal) {
        DmgPalette[] values = DmgPalette.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DmgPalette.DEFAULT;
    }
}
