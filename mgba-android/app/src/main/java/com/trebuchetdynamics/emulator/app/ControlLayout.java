package com.trebuchetdynamics.emulator.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

/**
 * Pure geometry for the emulated screen, the LOAD chip, and the on-screen
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
        final int key;
        final String label;
        final Shape shape;
        final float cx;
        final float cy;
        final float halfWidth;
        final float halfHeight;

        Control(int key, String label, Shape shape,
                float cx, float cy, float halfWidth, float halfHeight) {
            this.key = key;
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
    final float loadLeft;
    final float loadTop;
    final float loadRight;
    final float loadBottom;
    final float noticesLeft;
    final float noticesTop;
    final float noticesRight;
    final float noticesBottom;
    final List<Control> controls;

    private ControlLayout(float gameLeft, float gameTop, float gameRight, float gameBottom,
            float loadLeft, float loadTop, float loadRight, float loadBottom,
            float noticesLeft, float noticesTop, float noticesRight, float noticesBottom,
            List<Control> controls) {
        this.gameLeft = gameLeft;
        this.gameTop = gameTop;
        this.gameRight = gameRight;
        this.gameBottom = gameBottom;
        this.loadLeft = loadLeft;
        this.loadTop = loadTop;
        this.loadRight = loadRight;
        this.loadBottom = loadBottom;
        this.noticesLeft = noticesLeft;
        this.noticesTop = noticesTop;
        this.noticesRight = noticesRight;
        this.noticesBottom = noticesBottom;
        this.controls = Collections.unmodifiableList(controls);
    }

    static ControlLayout of(float width, float height) {
        return width > height ? landscape(width, height) : portrait(width, height);
    }

    /**
     * Existing shape (game on top, controls below), unchanged visually, but
     * sized off {@code unit = min(width, height)} so it shares one sizing
     * rule with {@link #landscape}. In portrait {@code unit == width}
     * always, so these constants reproduce the pre-fix pixel values exactly.
     *
     * <p>The LOAD/NOTICES chips sit in a header band above the game rect
     * (not overlaid on it), anchored to the game's right edge.
     */
    private static ControlLayout portrait(float width, float height) {
        float unit = Math.min(width, height);
        float margin = unit * 0.025f;

        float chipHeight = Math.max(54, unit * 0.075f);
        float chipGap = unit * 0.02f;
        float headerHeight = chipHeight + chipGap * 2;

        float gameWidth = width - margin * 2;
        float gameHeight = gameWidth * MgbaSession.VIDEO_HEIGHT / MgbaSession.VIDEO_WIDTH;
        if (gameHeight > height * 0.5f) {
            gameHeight = height * 0.5f;
            gameWidth = gameHeight * MgbaSession.VIDEO_WIDTH / MgbaSession.VIDEO_HEIGHT;
        }
        float gameLeft = (width - gameWidth) / 2;
        float gameTop = headerHeight;
        float gameRight = gameLeft + gameWidth;
        float gameBottom = gameTop + gameHeight;

        float loadWidth = unit * 0.18f;
        float loadRight = gameRight - 12;
        float loadLeft = loadRight - loadWidth;
        float loadTop = chipGap;
        float loadBottom = loadTop + chipHeight;

        float noticesWidth = unit * 0.28f;
        float noticesRight = loadLeft - 12;
        float noticesLeft = noticesRight - noticesWidth;
        float noticesTop = chipGap;
        float noticesBottom = noticesTop + chipHeight;

        float controlsTop = gameBottom + margin;
        float controlsHeight = height - controlsTop;

        List<Control> controls = new ArrayList<>();

        float shoulderY = controlsTop + controlsHeight * 0.10f;
        float shoulderHalfWidth = unit * 0.11f;
        float shoulderHalfHeight = shoulderHalfWidth * 0.34f;
        controls.add(new Control(MgbaSession.KEY_L, "L", Shape.PILL,
                width * 0.16f, shoulderY, shoulderHalfWidth, shoulderHalfHeight));
        controls.add(new Control(MgbaSession.KEY_R, "R", Shape.PILL,
                width * 0.84f, shoulderY, shoulderHalfWidth, shoulderHalfHeight));

        float dpadX = width * 0.24f;
        float dpadY = controlsTop + controlsHeight * 0.42f;
        float dpadHalf = unit * 0.17f;
        controls.add(new Control(0, "", Shape.DPAD, dpadX, dpadY, dpadHalf, dpadHalf));

        float buttonRadius = unit * 0.09f;
        float buttonY = controlsTop + controlsHeight * 0.39f;
        controls.add(new Control(MgbaSession.KEY_A, "A", Shape.CIRCLE,
                width * 0.80f, buttonY - buttonRadius * 0.45f, buttonRadius, buttonRadius));
        controls.add(new Control(MgbaSession.KEY_B, "B", Shape.CIRCLE,
                width * 0.65f, buttonY + buttonRadius * 0.45f, buttonRadius, buttonRadius));

        float smallY = controlsTop + controlsHeight * 0.78f;
        float smallHalfWidth = unit * 0.075f;
        float smallHalfHeight = smallHalfWidth * 0.34f;
        controls.add(new Control(MgbaSession.KEY_SELECT, "SELECT", Shape.PILL,
                width * 0.39f, smallY, smallHalfWidth, smallHalfHeight));
        controls.add(new Control(MgbaSession.KEY_START, "START", Shape.PILL,
                width * 0.61f, smallY, smallHalfWidth, smallHalfHeight));

        return new ControlLayout(gameLeft, gameTop, gameRight, gameBottom,
                loadLeft, loadTop, loadRight, loadBottom,
                noticesLeft, noticesTop, noticesRight, noticesBottom, controls);
    }

    /**
     * Handheld-emulator arrangement: game centred, D-pad in the left gutter,
     * A/B in the right gutter, shoulders in the top corners, SELECT/START
     * bottom-centre. The game rect is sized as large as fits within ~92% of
     * height at the console's 3:2 aspect ratio, then clamped so it never
     * reaches into either gutter.
     *
     * <p>The LOAD/NOTICES chips sit in a header band above the game rect
     * (not overlaid on it), anchored to the game's right edge.
     */
    private static ControlLayout landscape(float width, float height) {
        float unit = Math.min(width, height);

        float dpadHalf = unit * 0.17f;
        float abRadius = unit * 0.10f;
        float abOffset = abRadius;
        float clusterMargin = unit * 0.04f;

        // Half-width each gutter must have so its cluster (D-pad, or the
        // diagonal A/B pair) clears both the game edge and the screen edge.
        float leftClusterHalfExtent = dpadHalf + clusterMargin;
        float rightClusterHalfExtent = abRadius + abOffset + clusterMargin;
        float gutterWidth = 2f * Math.max(leftClusterHalfExtent, rightClusterHalfExtent);

        float chipHeight = Math.max(54, unit * 0.075f);
        float chipGap = unit * 0.02f;
        float topMargin = chipHeight + chipGap * 2;
        float bottomMargin = unit * 0.02f;
        float selectStartHalfHeight = unit * 0.035f;
        float bottomReserve = selectStartHalfHeight * 2f + bottomMargin * 2f;

        float maxGameHeight = Math.min(height * 0.92f, height - topMargin - bottomReserve);
        float maxGameWidthByHeight = maxGameHeight * MgbaSession.VIDEO_WIDTH / MgbaSession.VIDEO_HEIGHT;
        float maxGameWidthByGutter = width - 2f * gutterWidth;

        float gameWidth = Math.min(maxGameWidthByHeight, maxGameWidthByGutter);
        float gameHeight = gameWidth * MgbaSession.VIDEO_HEIGHT / MgbaSession.VIDEO_WIDTH;

        float gameLeft = (width - gameWidth) / 2;
        float gameTop = topMargin;
        float gameRight = gameLeft + gameWidth;
        float gameBottom = gameTop + gameHeight;

        float loadWidth = unit * 0.18f;
        float loadRight = gameRight - 12;
        float loadLeft = loadRight - loadWidth;
        float loadTop = chipGap;
        float loadBottom = loadTop + chipHeight;

        float noticesWidth = unit * 0.28f;
        float noticesRight = loadLeft - 12;
        float noticesLeft = noticesRight - noticesWidth;
        float noticesTop = chipGap;
        float noticesBottom = noticesTop + chipHeight;

        List<Control> controls = new ArrayList<>();

        float dpadX = gameLeft / 2f;
        float dpadY = height * 0.62f;
        controls.add(new Control(0, "", Shape.DPAD, dpadX, dpadY, dpadHalf, dpadHalf));

        float abCenterX = gameRight + (width - gameRight) / 2f;
        float abCenterY = height * 0.62f;
        controls.add(new Control(MgbaSession.KEY_A, "A", Shape.CIRCLE,
                abCenterX + abOffset, abCenterY - abOffset, abRadius, abRadius));
        controls.add(new Control(MgbaSession.KEY_B, "B", Shape.CIRCLE,
                abCenterX - abOffset, abCenterY + abOffset, abRadius, abRadius));

        float shoulderHalfWidth = unit * 0.11f;
        float shoulderHalfHeight = unit * 0.045f;
        float shoulderEdgeMargin = unit * 0.02f;
        controls.add(new Control(MgbaSession.KEY_L, "L", Shape.PILL,
                shoulderHalfWidth + shoulderEdgeMargin, shoulderHalfHeight + shoulderEdgeMargin,
                shoulderHalfWidth, shoulderHalfHeight));
        controls.add(new Control(MgbaSession.KEY_R, "R", Shape.PILL,
                width - shoulderHalfWidth - shoulderEdgeMargin, shoulderHalfHeight + shoulderEdgeMargin,
                shoulderHalfWidth, shoulderHalfHeight));

        float smallHalfWidth = unit * 0.09f;
        float smallHalfHeight = unit * 0.035f;
        float smallGap = unit * 0.02f;
        float smallY = height - bottomMargin - smallHalfHeight;
        controls.add(new Control(MgbaSession.KEY_SELECT, "SELECT", Shape.PILL,
                width / 2f - smallHalfWidth - smallGap / 2f, smallY, smallHalfWidth, smallHalfHeight));
        controls.add(new Control(MgbaSession.KEY_START, "START", Shape.PILL,
                width / 2f + smallHalfWidth + smallGap / 2f, smallY, smallHalfWidth, smallHalfHeight));

        return new ControlLayout(gameLeft, gameTop, gameRight, gameBottom,
                loadLeft, loadTop, loadRight, loadBottom,
                noticesLeft, noticesTop, noticesRight, noticesBottom, controls);
    }

    /**
     * Single source of truth for hit-testing: the OR of every control this
     * point falls in. The D-pad's box decomposes into direction bits around
     * a dead zone at its centre; diagonals set two bits at once.
     */
    int keysAt(float x, float y) {
        int keys = 0;
        for (Control control : controls) {
            if (control.shape == Shape.DPAD) {
                if (control.contains(x, y)) {
                    float dx = x - control.cx;
                    float dy = y - control.cy;
                    float dead = control.halfWidth * 0.18f;
                    if (dx < -dead) keys |= MgbaSession.KEY_LEFT;
                    if (dx > dead) keys |= MgbaSession.KEY_RIGHT;
                    if (dy < -dead) keys |= MgbaSession.KEY_UP;
                    if (dy > dead) keys |= MgbaSession.KEY_DOWN;
                }
            } else if (control.contains(x, y)) {
                keys |= control.key;
            }
        }
        return keys;
    }

    boolean isLoadHit(float x, float y) {
        float pad = 12f;
        return x >= loadLeft - pad && x <= loadRight + pad
                && y >= loadTop - pad && y <= loadBottom + pad * 2f;
    }

    boolean isNoticesHit(float x, float y) {
        float pad = 12f;
        return x >= noticesLeft - pad && x <= noticesRight + pad
                && y >= noticesTop - pad && y <= noticesBottom + pad * 2f;
    }
}
