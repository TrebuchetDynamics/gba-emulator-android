package com.trebuchetdynamics.emulator.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

/**
 * Pure geometry for the emulated screen, menu affordance, and on-screen
 * gamepad. Deliberately has no {@code android.*} imports so it can be
 * unit-tested on the JVM (an {@code android.graphics.RectF} throws
 * "Stub!" outside instrumented tests).
 *
 * <p>This is the single source of truth for control positions: {@code
 * EmulatorView} draws directly from {@link #controls} and hit-tests through
 * {@link #keysAt}, so what is drawn and what is touchable can never drift
 * apart.
 */
final class ControlLayout {

    enum Shape { CIRCLE, PILL, DPAD }

    static final class Control {
        final int id;
        final int key;
        final boolean turbo;
        final String label;
        final Shape shape;
        final float cx;
        final float cy;
        final float halfWidth;
        final float halfHeight;

        Control(int key, String label, Shape shape,
                float cx, float cy, float halfWidth, float halfHeight) {
            this(key, key, false, label, shape, cx, cy, halfWidth, halfHeight);
        }

        Control(int id, int key, boolean turbo, String label, Shape shape,
                float cx, float cy, float halfWidth, float halfHeight) {
            this.id = id;
            this.key = key;
            this.turbo = turbo;
            this.label = label;
            this.shape = shape;
            this.cx = cx;
            this.cy = cy;
            this.halfWidth = halfWidth;
            this.halfHeight = halfHeight;
        }

        boolean contains(float x, float y) {
            float dx = x - cx;
            float dy = y - cy;
            if (shape == Shape.CIRCLE) {
                float nx = dx / halfWidth;
                float ny = dy / halfHeight;
                return nx * nx + ny * ny <= 1f;
            }
            return Math.abs(dx) <= halfWidth && Math.abs(dy) <= halfHeight;
        }
    }

    final float gameLeft;
    final float gameTop;
    final float gameRight;
    final float gameBottom;
    final float menuLeft;
    final float menuTop;
    final float menuRight;
    final float menuBottom;
    final float muteLeft;
    final float muteTop;
    final float muteRight;
    final float muteBottom;
    final List<Control> controls;

    private ControlLayout(float gameLeft, float gameTop, float gameRight, float gameBottom,
            float menuLeft, float menuTop, float menuRight, float menuBottom,
            float muteLeft, float muteTop, float muteRight, float muteBottom,
            List<Control> controls) {
        this.gameLeft = gameLeft;
        this.gameTop = gameTop;
        this.gameRight = gameRight;
        this.gameBottom = gameBottom;
        this.menuLeft = menuLeft;
        this.menuTop = menuTop;
        this.menuRight = menuRight;
        this.menuBottom = menuBottom;
        this.muteLeft = muteLeft;
        this.muteTop = muteTop;
        this.muteRight = muteRight;
        this.muteBottom = muteBottom;
        this.controls = Collections.unmodifiableList(controls);
    }

    static ControlLayout of(float width, float height) {
        return of(width, height, ControlOverrides.EMPTY);
    }

    static ControlLayout of(float width, float height, ControlOverrides overrides) {
        return of(width, height, overrides,
                MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, true);
    }

    static ControlLayout of(float width, float height, ControlOverrides overrides,
            float srcW, float srcH, boolean hasShoulders) {
        return of(width, height, overrides, srcW, srcH, hasShoulders, MacroControls.EMPTY);
    }

    static ControlLayout of(float width, float height, ControlOverrides overrides,
            float srcW, float srcH, boolean hasShoulders, MacroControls macros) {
        ControlLayout base = width > height
                ? landscape(width, height, srcW, srcH, hasShoulders)
                : portrait(width, height, srcW, srcH, hasShoulders);
        List<Control> controls = new ArrayList<>(base.controls);
        float unit = Math.min(width, height);
        if (macros != null) {
            for (MacroControls.Macro macro : macros.values()) {
                controls.add(new Control(macro.layoutId(), macro.keyMask, macro.turbo,
                        macro.shortLabel(), Shape.PILL, width / 2f, height / 2f,
                        unit * 0.13f, unit * 0.055f));
            }
        }
        ControlLayout withMacros = new ControlLayout(
                base.gameLeft, base.gameTop, base.gameRight, base.gameBottom,
                base.menuLeft, base.menuTop, base.menuRight, base.menuBottom,
                base.muteLeft, base.muteTop, base.muteRight, base.muteBottom, controls);
        return applyOverrides(withMacros, overrides, width, height);
    }

    private static ControlLayout applyOverrides(ControlLayout layout,
            ControlOverrides overrides, float width, float height) {
        if (overrides == null || overrides == ControlOverrides.EMPTY) {
            return layout;
        }
        List<Control> applied = new ArrayList<>(layout.controls.size());
        boolean changed = false;
        for (Control c : layout.controls) {
            if (overrides.has(c.id)) {
                applied.add(applyOverride(c, overrides, width, height));
                changed = true;
            } else {
                applied.add(c);
            }
        }
        if (!changed) {
            return layout;
        }
        return new ControlLayout(
                layout.gameLeft, layout.gameTop, layout.gameRight, layout.gameBottom,
                layout.menuLeft, layout.menuTop, layout.menuRight, layout.menuBottom,
                layout.muteLeft, layout.muteTop, layout.muteRight, layout.muteBottom,
                applied);
    }

    private static Control applyOverride(Control c, ControlOverrides o, float w, float h) {
        float halfWidth = c.halfWidth * o.scale(c.id);
        float halfHeight = c.halfHeight * o.scale(c.id);
        float cx = clampCenter(o.normCx(c.id) * w, halfWidth, w);
        float cy = clampCenter(o.normCy(c.id) * h, halfHeight, h);
        return new Control(c.id, c.key, c.turbo, c.label, c.shape,
                cx, cy, halfWidth, halfHeight);
    }

    /** Keep a control's box fully within [0, extent]; center it if it cannot fit. */
    private static float clampCenter(float center, float half, float extent) {
        if (half * 2f >= extent) {
            return extent / 2f;
        }
        if (center < half) {
            return half;
        }
        return Math.min(center, extent - half);
    }

    /** Game above large, thumb-sized controls; only the menu remains as chrome. */
    private static ControlLayout portrait(float width, float height,
            float srcW, float srcH, boolean hasShoulders) {
        float unit = Math.min(width, height);
        float margin = unit * 0.025f;

        float gameWidth = width;
        float gameHeight = gameWidth * srcH / srcW;
        if (gameHeight > height * 0.5f) {
            gameHeight = height * 0.5f;
            gameWidth = gameHeight * srcW / srcH;
        }
        float gameLeft = (width - gameWidth) / 2f;
        float gameTop = unit * 0.09f;
        float gameRight = gameLeft + gameWidth;
        float gameBottom = gameTop + gameHeight;

        float menuSize = unit * 0.13f;
        float menuLeft = margin;
        float menuRight = menuLeft + menuSize;
        float menuBottom = height - margin;
        float menuTop = menuBottom - menuSize;
        float muteRight = width - margin;
        float muteLeft = muteRight - menuSize;
        float muteTop = menuTop;
        float muteBottom = menuBottom;

        float controlsTop = gameBottom + margin;
        float controlsHeight = height - controlsTop;
        float mainY = controlsTop + controlsHeight * 0.49f;
        List<Control> controls = new ArrayList<>();

        float shoulderHalfWidth = unit * 0.15f;
        float shoulderHalfHeight = unit * 0.065f;
        float shoulderY = controlsTop + controlsHeight * 0.18f;
        if (hasShoulders) {
            controls.add(new Control(MgbaSession.KEY_L, "L", Shape.PILL,
                    width * 0.20f, shoulderY, shoulderHalfWidth, shoulderHalfHeight));
            controls.add(new Control(MgbaSession.KEY_R, "R", Shape.PILL,
                    width * 0.80f, shoulderY, shoulderHalfWidth, shoulderHalfHeight));
        }

        float dpadHalf = unit * 0.235f;
        controls.add(new Control(0, "", Shape.DPAD,
                width * 0.25f, mainY, dpadHalf, dpadHalf));

        float buttonRadius = unit * 0.12f;
        controls.add(new Control(MgbaSession.KEY_B, "B", Shape.CIRCLE,
                width * 0.63f, mainY, buttonRadius, buttonRadius));
        controls.add(new Control(MgbaSession.KEY_A, "A", Shape.CIRCLE,
                width * 0.87f, mainY, buttonRadius, buttonRadius));

        float smallY = controlsTop + controlsHeight * 0.80f;
        float smallHalfWidth = unit * 0.15f;
        float smallHalfHeight = unit * 0.055f;
        controls.add(new Control(MgbaSession.KEY_SELECT, "SELECT", Shape.PILL,
                width / 3f, smallY, smallHalfWidth, smallHalfHeight));
        controls.add(new Control(MgbaSession.KEY_START, "START", Shape.PILL,
                width * 2f / 3f, smallY, smallHalfWidth, smallHalfHeight));

        return new ControlLayout(gameLeft, gameTop, gameRight, gameBottom,
                menuLeft, menuTop, menuRight, menuBottom,
                muteLeft, muteTop, muteRight, muteBottom, controls);
    }

    /** Maximized game with controls in the natural left and right thumb arcs. */
    private static ControlLayout landscape(float width, float height,
            float srcW, float srcH, boolean hasShoulders) {
        float unit = Math.min(width, height);
        float gameHeight = height;
        float gameWidth = gameHeight * srcW / srcH;
        if (gameWidth > width) {
            gameWidth = width;
            gameHeight = gameWidth * srcH / srcW;
        }
        float gameLeft = (width - gameWidth) / 2f;
        float gameTop = 0f;
        float gameRight = gameLeft + gameWidth;
        float gameBottom = gameHeight;

        float menuSize = unit * 0.13f;
        float menuLeft = unit * 0.04f;
        float menuRight = menuLeft + menuSize;
        float menuBottom = height - unit * 0.02f;
        float menuTop = menuBottom - menuSize;
        float muteRight = width - unit * 0.04f;
        float muteLeft = muteRight - menuSize;
        float muteTop = menuTop;
        float muteBottom = menuBottom;

        float leftGutter = gameLeft;
        float rightGutter = width - gameRight;
        List<Control> controls = new ArrayList<>();

        float dpadHalf = unit * 0.20f;
        float dpadX = Math.max(dpadHalf + unit * 0.02f, leftGutter * 0.55f);
        float mainY = height * 0.62f;
        controls.add(new Control(0, "", Shape.DPAD, dpadX, mainY, dpadHalf, dpadHalf));

        float abRadius = unit * 0.08f;
        controls.add(new Control(MgbaSession.KEY_B, "B", Shape.CIRCLE,
                gameRight + rightGutter * 0.23f, mainY, abRadius, abRadius));
        controls.add(new Control(MgbaSession.KEY_A, "A", Shape.CIRCLE,
                gameRight + rightGutter * 0.74f, mainY, abRadius, abRadius));

        float shoulderHalfWidth = unit * 0.145f;
        float shoulderHalfHeight = unit * 0.05f;
        float sideCenter = unit * 0.22f;
        if (hasShoulders) {
            controls.add(new Control(MgbaSession.KEY_L, "L", Shape.PILL,
                    sideCenter, height * 0.29f, shoulderHalfWidth, shoulderHalfHeight));
            controls.add(new Control(MgbaSession.KEY_R, "R", Shape.PILL,
                    width - sideCenter, height * 0.29f, shoulderHalfWidth, shoulderHalfHeight));
        }

        float smallHalfWidth = unit * 0.08f;
        float smallHalfHeight = unit * 0.03f;
        float smallY = height * 0.13f;
        controls.add(new Control(MgbaSession.KEY_SELECT, "SELECT", Shape.PILL,
                gameRight + rightGutter * 0.23f, smallY, smallHalfWidth, smallHalfHeight));
        controls.add(new Control(MgbaSession.KEY_START, "START", Shape.PILL,
                gameRight + rightGutter * 0.74f, smallY, smallHalfWidth, smallHalfHeight));

        return new ControlLayout(gameLeft, gameTop, gameRight, gameBottom,
                menuLeft, menuTop, menuRight, menuBottom,
                muteLeft, muteTop, muteRight, muteBottom, controls);
    }

    static final class Input {
        final int normalKeys;
        final int turboKeys;

        Input(int normalKeys, int turboKeys) {
            this.normalKeys = normalKeys;
            this.turboKeys = turboKeys;
        }
    }

    /** Hit-tested normal and Turbo channels at one point. */
    Input inputAt(float x, float y) {
        int normal = 0;
        int turbo = 0;
        for (Control control : controls) {
            int hit = 0;
            if (control.shape == Shape.DPAD && control.contains(x, y)) {
                float dx = x - control.cx;
                float dy = y - control.cy;
                float dead = control.halfWidth * 0.18f;
                if (dx < -dead) hit |= MgbaSession.KEY_LEFT;
                if (dx > dead) hit |= MgbaSession.KEY_RIGHT;
                if (dy < -dead) hit |= MgbaSession.KEY_UP;
                if (dy > dead) hit |= MgbaSession.KEY_DOWN;
            } else if (control.shape != Shape.DPAD && control.contains(x, y)) {
                hit = control.key;
            }
            if (control.turbo) {
                turbo |= hit;
            } else {
                normal |= hit;
            }
        }
        return new Input(normal, turbo);
    }

    /** Compatibility view of both input channels. */
    int keysAt(float x, float y) {
        Input input = inputAt(x, y);
        return input.normalKeys | input.turboKeys;
    }

    boolean isMenuHit(float x, float y) {
        float pad = 12f;
        return x >= menuLeft - pad && x <= menuRight + pad
                && y >= menuTop - pad && y <= menuBottom + pad * 2f;
    }

    boolean isMuteHit(float x, float y) {
        float pad = 12f;
        return x >= muteLeft - pad && x <= muteRight + pad
                && y >= muteTop - pad && y <= muteBottom + pad * 2f;
    }
}
