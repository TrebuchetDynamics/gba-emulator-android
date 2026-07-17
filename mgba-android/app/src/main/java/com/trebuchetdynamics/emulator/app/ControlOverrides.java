package com.trebuchetdynamics.emulator.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure per-control layout overrides: for each control {@code key}, a normalized
 * center (fraction of width/height) and a uniform scale. No {@code android.*}
 * imports, so it is JVM-testable.
 *
 * <p>{@link #EMPTY} is a shared read-only default handed to
 * {@link ControlLayout#of(float, float, ControlOverrides)}; never mutate it.
 * {@link #parse} always returns a fresh mutable instance.
 */
final class ControlOverrides {

    /** Shared empty default. Do not mutate. */
    static final ControlOverrides EMPTY = new ControlOverrides();

    private static final class Entry {
        final float normCx;
        final float normCy;
        final float scale;

        Entry(float normCx, float normCy, float scale) {
            this.normCx = normCx;
            this.normCy = normCy;
            this.scale = scale;
        }
    }

    private final Map<Integer, Entry> map = new LinkedHashMap<>();

    boolean has(int key) {
        return map.containsKey(key);
    }

    float normCx(int key) {
        return map.get(key).normCx;
    }

    float normCy(int key) {
        return map.get(key).normCy;
    }

    float scale(int key) {
        return map.get(key).scale;
    }

    void put(int key, float normCx, float normCy, float scale) {
        map.put(key, new Entry(clampNorm(normCx), clampNorm(normCy), clampScale(scale)));
    }

    void clear() {
        map.clear();
    }

    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Entry> e : new TreeMap<>(map).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            Entry v = e.getValue();
            sb.append(e.getKey()).append(':')
                    .append(fmt(v.normCx)).append(':')
                    .append(fmt(v.normCy)).append(':')
                    .append(fmt(v.scale));
        }
        return sb.toString();
    }

    static ControlOverrides parse(String s) {
        ControlOverrides out = new ControlOverrides();
        if (s == null || s.trim().isEmpty()) {
            return out;
        }
        for (String field : s.split(",")) {
            if (field.trim().isEmpty()) {
                continue;
            }
            String[] parts = field.split(":");
            if (parts.length != 4) {
                continue;
            }
            try {
                int key = Integer.parseInt(parts[0].trim());
                float ncx = Float.parseFloat(parts[1].trim());
                float ncy = Float.parseFloat(parts[2].trim());
                float sc = Float.parseFloat(parts[3].trim());
                out.put(key, ncx, ncy, sc);
            } catch (NumberFormatException ignored) {
                // skip the malformed field, keep the good ones
            }
        }
        return out;
    }

    static float clampNorm(float v) {
        if (v < 0f) {
            return 0f;
        }
        return Math.min(v, 1f);
    }

    static float clampScale(float v) {
        if (v < 0.5f) {
            return 0.5f;
        }
        return Math.min(v, 2f);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.4f", v);
    }
}
