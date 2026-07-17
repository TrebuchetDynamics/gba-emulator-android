package com.trebuchetdynamics.emulator.app;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure, JVM-testable map from Android key codes to GBA key bitmasks.
 *
 * <p>No {@code android.*} imports: key codes and GBA key bitmasks are plain
 * ints supplied by callers (see {@link GamepadDefaults} for the real values).
 */
final class KeyBindings {
    private final Map<Integer, Integer> map;

    private KeyBindings(Map<Integer, Integer> initial) {
        this.map = new LinkedHashMap<>(initial);
    }

    /** A live, mutable binding set seeded from {@code initial}. */
    static KeyBindings of(Map<Integer, Integer> initial) {
        return new KeyBindings(initial);
    }

    /** The GBA key bitmask bound to {@code keyCode}, or 0 if unbound. */
    int gbaKeyFor(int keyCode) {
        Integer v = map.get(keyCode);
        return v == null ? 0 : v;
    }

    /**
     * Bind {@code keyCode} to {@code gbaKey}. A GBA button has exactly one key
     * in the editable map, and a physical key drives exactly one GBA button:
     * any key(s) already bound to {@code gbaKey} are dropped, and {@code put}
     * overwrites any prior binding of {@code keyCode}.
     */
    void bind(int gbaKey, int keyCode) {
        Iterator<Map.Entry<Integer, Integer>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() == gbaKey) {
                it.remove();
            }
        }
        map.put(keyCode, gbaKey);
    }

    /** Some key bound to {@code gbaKey}, or -1 if none. */
    int keyCodeFor(int gbaKey) {
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (e.getValue() == gbaKey) {
                return e.getKey();
            }
        }
        return -1;
    }

    /** Restore the injected defaults. */
    void reset(Map<Integer, Integer> defaults) {
        map.clear();
        map.putAll(defaults);
    }

    /** Deterministic {@code "keycode:gbakey,..."} ordered by keycode. */
    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(map).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Parse a stored string; on null/empty/garbage (or a parse that yields no
     * usable pairs) fall back to {@code defaults}.
     */
    static KeyBindings parse(String s, Map<Integer, Integer> defaults) {
        if (s == null || s.trim().isEmpty()) {
            return new KeyBindings(defaults);
        }
        Map<Integer, Integer> parsed = new LinkedHashMap<>();
        try {
            for (String pair : s.split(",")) {
                if (pair.trim().isEmpty()) {
                    continue;
                }
                String[] kv = pair.split(":");
                if (kv.length != 2) {
                    continue;
                }
                parsed.put(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim()));
            }
        } catch (NumberFormatException e) {
            return new KeyBindings(defaults);
        }
        return new KeyBindings(parsed.isEmpty() ? defaults : parsed);
    }
}
