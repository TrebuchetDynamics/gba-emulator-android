package com.trebuchetdynamics.emulator.app;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure, validated definitions for custom touch buttons. */
final class MacroControls {
    static final int MAX_CONTROLS = 8;
    private static final int LAYOUT_ID_BASE = 0x10000;
    static final int SUPPORTED_KEYS = MgbaSession.KEY_LEFT | MgbaSession.KEY_UP
            | MgbaSession.KEY_RIGHT | MgbaSession.KEY_DOWN | MgbaSession.KEY_A
            | MgbaSession.KEY_B | MgbaSession.KEY_L | MgbaSession.KEY_R
            | MgbaSession.KEY_SELECT | MgbaSession.KEY_START;
    static final MacroControls EMPTY = new MacroControls();

    static final class Macro {
        final int slot;
        final int keyMask;
        final boolean turbo;

        Macro(int slot, int keyMask, boolean turbo) {
            this.slot = slot;
            this.keyMask = keyMask;
            this.turbo = turbo;
        }

        int layoutId() {
            return layoutIdForSlot(slot);
        }

        String shortLabel() {
            String label = Integer.bitCount(keyMask) > 3
                    ? Integer.bitCount(keyMask) + " keys"
                    : joinLabels(keyMask, true);
            return turbo ? label + " ⚡" : label;
        }

        String contentLabel() {
            String label = joinLabels(keyMask, false);
            return turbo ? "Turbo " + label : label;
        }
    }

    private final Map<Integer, Macro> bySlot = new TreeMap<>();

    int size() {
        return bySlot.size();
    }

    boolean isFull() {
        return size() >= MAX_CONTROLS;
    }

    List<Macro> values() {
        return Collections.unmodifiableList(new ArrayList<>(bySlot.values()));
    }

    Macro add(int keyMask, boolean turbo) {
        requireMask(keyMask);
        if (containsDefinition(keyMask, turbo)) {
            throw new IllegalArgumentException("That custom button already exists");
        }
        if (isFull()) {
            throw new IllegalStateException("Maximum 8 custom buttons per orientation");
        }
        for (int slot = 1; slot <= MAX_CONTROLS; slot++) {
            if (!bySlot.containsKey(slot)) {
                Macro macro = new Macro(slot, keyMask, turbo);
                bySlot.put(slot, macro);
                return macro;
            }
        }
        throw new AssertionError("No free macro slot");
    }

    boolean removeLayoutId(int layoutId) {
        return bySlot.remove(slotForLayoutId(layoutId)) != null;
    }

    Macro macroForLayoutId(int layoutId) {
        return isMacroLayoutId(layoutId) ? bySlot.get(slotForLayoutId(layoutId)) : null;
    }

    boolean containsDefinition(int keyMask, boolean turbo) {
        for (Macro macro : bySlot.values()) {
            if (macro.keyMask == keyMask && macro.turbo == turbo) {
                return true;
            }
        }
        return false;
    }

    void clear() {
        bySlot.clear();
    }

    MacroControls copy() {
        MacroControls copy = new MacroControls();
        copy.bySlot.putAll(bySlot);
        return copy;
    }

    String serialize() {
        StringBuilder out = new StringBuilder();
        for (Macro macro : bySlot.values()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(macro.slot).append(':').append(macro.keyMask)
                    .append(':').append(macro.turbo);
        }
        return out.toString();
    }

    static MacroControls parse(String stored) {
        MacroControls out = new MacroControls();
        if (stored == null || stored.trim().isEmpty()) {
            return out;
        }
        for (String record : stored.split(",")) {
            String[] fields = record.split(":");
            if (fields.length != 3) {
                continue;
            }
            try {
                int slot = Integer.parseInt(fields[0]);
                int mask = Integer.parseInt(fields[1]);
                if (!"true".equals(fields[2]) && !"false".equals(fields[2])) {
                    continue;
                }
                boolean turbo = Boolean.parseBoolean(fields[2]);
                if (slot < 1 || slot > MAX_CONTROLS || out.bySlot.containsKey(slot)
                        || !isValidMask(mask) || out.containsDefinition(mask, turbo)) {
                    continue;
                }
                out.bySlot.put(slot, new Macro(slot, mask, turbo));
            } catch (NumberFormatException ignored) {
                // Keep valid records when one record is malformed.
            }
        }
        return out;
    }

    static int layoutIdForSlot(int slot) {
        return LAYOUT_ID_BASE + slot;
    }

    static boolean isMacroLayoutId(int id) {
        return id > LAYOUT_ID_BASE && id <= LAYOUT_ID_BASE + MAX_CONTROLS;
    }

    private static int slotForLayoutId(int id) {
        return id - LAYOUT_ID_BASE;
    }

    private static boolean isValidMask(int mask) {
        return mask != 0 && (mask & ~SUPPORTED_KEYS) == 0;
    }

    private static void requireMask(int mask) {
        if (!isValidMask(mask)) {
            throw new IllegalArgumentException("Choose at least one input");
        }
    }

    private static String joinLabels(int mask, boolean symbols) {
        int[] keys = { MgbaSession.KEY_LEFT, MgbaSession.KEY_UP, MgbaSession.KEY_RIGHT,
                MgbaSession.KEY_DOWN, MgbaSession.KEY_A, MgbaSession.KEY_B,
                MgbaSession.KEY_L, MgbaSession.KEY_R, MgbaSession.KEY_SELECT,
                MgbaSession.KEY_START };
        String[] shortNames = { "←", "↑", "→", "↓", "A", "B", "L", "R", "Select", "Start" };
        String[] fullNames = { "Left", "Up", "Right", "Down", "A", "B", "L", "R",
                "Select", "Start" };
        List<String> names = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if ((mask & keys[i]) != 0) {
                names.add(symbols ? shortNames[i] : fullNames[i]);
            }
        }
        String separator = symbols ? " + " : " plus ";
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(name);
        }
        return out.toString();
    }
}
