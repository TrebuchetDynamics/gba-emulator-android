package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

final class EmulatorView extends View {
    private static final int CONTROL_COLOR = Color.rgb(100, 113, 132);
    private static final int CONTROL_PRESSED_COLOR = Color.rgb(113, 153, 222);
    private static final long AUTO_HIDE_CONTROLS_MS = 10_000L;

    private int minAlpha = 60;              // was MIN_ALPHA constant
    private int maxAlpha = 255;
    private boolean hapticsEnabled = true;
    private boolean integerScale = true;    // false = fill
    private boolean touchControlsHidden;
    private boolean autoHideTouchControls;
    private boolean touchActive;
    private boolean revealingHiddenControls;
    private long lastTouchMs = SystemClock.uptimeMillis();
    private boolean muted;
    private String speedIndicator;
    private long speedIndicatorUntilMs;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile Bitmap frame = Bitmap.createBitmap(
            MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
    private volatile int videoWidth = MgbaSession.VIDEO_WIDTH;
    private volatile int videoHeight = MgbaSession.VIDEO_HEIGHT;
    private volatile boolean hasShoulders = true;
    private final Object frameLock = new Object();
    private final RectF gameRect = new RectF();
    private final RectF frameDst = new RectF();
    private final Runnable requestRom;
    private final Runnable requestMenu;
    private final Runnable requestMute;

    private ControlLayout layout = ControlLayout.of(1, 1);
    private ControlOverrides portraitOverrides = ControlOverrides.EMPTY;
    private ControlOverrides landscapeOverrides = ControlOverrides.EMPTY;
    private MacroControls portraitMacros = MacroControls.EMPTY;
    private MacroControls landscapeMacros = MacroControls.EMPTY;

    private volatile int touchKeys;
    private volatile int touchTurboKeys;
    private volatile int hardwareKeys;
    private volatile int resolvedFrameKeys;
    private int previousTouchKeys;
    private volatile boolean hasFrame;
    private volatile String status = "Tap to load a GBA ROM";

    public EmulatorView(Context context) {
        this(context, () -> {}, () -> {}, () -> {});
    }

    EmulatorView(Context context, Runnable requestRom, Runnable requestMenu, Runnable requestMute) {
        super(context);
        this.requestRom = requestRom;
        this.requestMenu = requestMenu;
        this.requestMute = requestMute;
        setBackgroundColor(Color.rgb(14, 16, 20));
        setContentDescription("Game screen and touch controls");
        paint.setFilterBitmap(false); // crisp GBA pixels, no bilinear smoothing
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    int keysForFrame(long frameIndex) {
        int resolved = FeelMath.applyTurbo(
                touchKeys | hardwareKeys, touchTurboKeys, frameIndex);
        resolvedFrameKeys = resolved;
        if (touchTurboKeys != 0) {
            postInvalidateOnAnimation();
        }
        return resolved;
    }

    private int visualKeys() {
        return resolvedFrameKeys;
    }

    void setHardwareKey(int key, boolean pressed) {
        if (pressed) {
            hardwareKeys |= key;
        } else {
            hardwareKeys &= ~key;
        }
        invalidate();
    }

    void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
    }

    void setIdleOpacityAlpha(int alpha) {
        minAlpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    void setActiveOpacityAlpha(int alpha) {
        maxAlpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    void setIntegerScale(boolean integer) {
        integerScale = integer;
        invalidate();
    }

    void setSmoothVideo(boolean smooth) {
        paint.setFilterBitmap(smooth);
        invalidate();
    }

    void setTouchControlsHidden(boolean hidden) {
        touchControlsHidden = hidden;
        if (hidden) {
            clearTouchInput();
        }
        invalidate();
    }

    void setAutoHideTouchControls(boolean enabled) {
        autoHideTouchControls = enabled;
        lastTouchMs = SystemClock.uptimeMillis();
        revealingHiddenControls = false;
        invalidate();
        if (enabled) {
            postInvalidateDelayed(AUTO_HIDE_CONTROLS_MS);
        }
    }

    void setMuted(boolean muted) {
        this.muted = muted;
        invalidate();
    }

    void showSpeedIndicator(String value) {
        speedIndicator = value;
        speedIndicatorUntilMs = SystemClock.uptimeMillis() + 1_400;
        invalidate();
        postInvalidateDelayed(1_450);
    }

    Bitmap copyGameFrame() {
        if (!hasFrame) {
            return null;
        }
        synchronized (frameLock) {
            return frame.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    void setControlOverrides(ControlOverrides portrait, ControlOverrides landscape) {
        setControlLayouts(portrait, portraitMacros, landscape, landscapeMacros);
    }

    void setControlLayouts(ControlOverrides portrait, MacroControls portraitMacros,
            ControlOverrides landscape, MacroControls landscapeMacros) {
        this.portraitOverrides = portrait == null ? ControlOverrides.EMPTY : portrait;
        this.landscapeOverrides = landscape == null ? ControlOverrides.EMPTY : landscape;
        this.portraitMacros = portraitMacros == null ? MacroControls.EMPTY : portraitMacros;
        this.landscapeMacros = landscapeMacros == null ? MacroControls.EMPTY : landscapeMacros;
        invalidate();
    }

    private ControlOverrides activeOverrides(int w, int h) {
        return w > h ? landscapeOverrides : portraitOverrides;
    }

    private MacroControls activeMacros(int w, int h) {
        return w > h ? landscapeMacros : portraitMacros;
    }

    void setStatus(String value) {
        status = value;
        if (!"Running".equals(value)) {
            hasFrame = false;
        }
        postInvalidate();
    }

    void setVideoSize(int w, int h, boolean hasShoulders) {
        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            videoWidth = w;
            videoHeight = h;
        }
        this.hasShoulders = hasShoulders;
        postInvalidate();
    }

    void publishFrame(int[] pixels) {
        synchronized (frameLock) {
            frame.setPixels(pixels, 0, videoWidth, 0, 0, videoWidth, videoHeight);
        }
        hasFrame = true;
        status = "Running";
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layout = ControlLayout.of(w, h, activeOverrides(w, h), videoWidth, videoHeight,
                hasShoulders, activeMacros(w, h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        layout = ControlLayout.of(getWidth(), getHeight(), activeOverrides(getWidth(), getHeight()),
                videoWidth, videoHeight, hasShoulders, activeMacros(getWidth(), getHeight()));
        gameRect.set(layout.gameLeft, layout.gameTop, layout.gameRight, layout.gameBottom);

        int controlAlpha = FeelMath.controlAlpha(minAlpha, maxAlpha);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(gameRect, 14, 14, paint); // letterbox backdrop
        if (hasFrame) {
            FeelMath.Box draw = integerScale && getWidth() <= getHeight()
                    ? FeelMath.integerScale(gameRect.left, gameRect.top, gameRect.right,
                            gameRect.bottom, videoWidth, videoHeight)
                    : FeelMath.fitScale(gameRect.left, gameRect.top, gameRect.right,
                            gameRect.bottom, videoWidth, videoHeight);
            frameDst.set(draw.left, draw.top, draw.right, draw.bottom);
            synchronized (frameLock) {
                canvas.drawBitmap(frame, null, frameDst, paint);
            }
        } else {
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(32, getWidth() * 0.045f));
            canvas.drawText(status, gameRect.centerX(), gameRect.centerY(), paint);
        }

        drawSpeedIndicator(canvas);
        drawMenuButton(canvas, controlAlpha);
        drawMuteButton(canvas, controlAlpha);

        if (!controlsHidden(SystemClock.uptimeMillis())) {
            for (ControlLayout.Control control : layout.controls) {
                switch (control.shape) {
                    case DPAD:
                        drawDpad(canvas, control.cx, control.cy, control.halfWidth, controlAlpha);
                        break;
                    case CIRCLE:
                        drawButton(canvas, control.cx, control.cy, control.halfWidth,
                                control.label, control.key, controlAlpha);
                        break;
                    case PILL:
                        drawPill(canvas, control, controlAlpha);
                        break;
                }
            }
        }
    }

    private void drawSpeedIndicator(Canvas canvas) {
        if (speedIndicator == null || SystemClock.uptimeMillis() >= speedIndicatorUntilMs) {
            return;
        }
        float textSize = Math.max(28, getWidth() * 0.035f);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.CENTER);
        float width = paint.measureText(speedIndicator) + textSize;
        float right = gameRect.right - textSize * 0.4f;
        float top = gameRect.top + textSize * 0.4f;
        paint.setColor(0xDD191C22);
        paint.setAlpha(255);
        canvas.drawRoundRect(right - width, top, right, top + textSize * 1.45f,
                textSize * 0.35f, textSize * 0.35f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(speedIndicator, right - width / 2f, top + textSize * 1.05f, paint);
    }

    private void drawMenuButton(Canvas canvas, int controlAlpha) {
        float cx = (layout.menuLeft + layout.menuRight) / 2f;
        float cy = (layout.menuTop + layout.menuBottom) / 2f;
        float radius = (layout.menuRight - layout.menuLeft) * 0.07f;
        paint.setColor(Color.WHITE);
        paint.setAlpha(controlAlpha);
        for (int i = -1; i <= 1; i++) {
            canvas.drawCircle(cx, cy + i * radius * 2.8f, radius, paint);
        }
    }

    private void drawMuteButton(Canvas canvas, int controlAlpha) {
        float cx = (layout.muteLeft + layout.muteRight) / 2f;
        float cy = (layout.muteTop + layout.muteBottom) / 2f;
        float size = (layout.muteRight - layout.muteLeft) * 0.5f;
        paint.setColor(Color.WHITE);
        paint.setAlpha(controlAlpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, size * 0.1f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - size * 0.48f, cy - size * 0.18f,
                cx - size * 0.20f, cy - size * 0.18f, paint);
        canvas.drawLine(cx - size * 0.20f, cy - size * 0.18f,
                cx + size * 0.08f, cy - size * 0.45f, paint);
        canvas.drawLine(cx + size * 0.08f, cy - size * 0.45f,
                cx + size * 0.08f, cy + size * 0.45f, paint);
        canvas.drawLine(cx + size * 0.08f, cy + size * 0.45f,
                cx - size * 0.20f, cy + size * 0.18f, paint);
        canvas.drawLine(cx - size * 0.20f, cy + size * 0.18f,
                cx - size * 0.48f, cy + size * 0.18f, paint);
        if (muted) {
            canvas.drawLine(cx - size * 0.48f, cy - size * 0.52f,
                    cx + size * 0.52f, cy + size * 0.52f, paint);
        } else {
            canvas.drawArc(cx, cy - size * 0.35f, cx + size * 0.52f, cy + size * 0.35f,
                    -48f, 96f, false, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawDpad(Canvas canvas, float cx, float cy, float radius, int alpha) {
        int directions = MgbaSession.KEY_UP | MgbaSession.KEY_DOWN
                | MgbaSession.KEY_LEFT | MgbaSession.KEY_RIGHT;
        paint.setColor((visualKeys() & directions) != 0
                ? CONTROL_PRESSED_COLOR : CONTROL_COLOR);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        float arm = radius * 0.42f;
        canvas.drawRoundRect(cx - arm, cy - radius, cx + arm, cy + radius, 14, 14, paint);
        canvas.drawRoundRect(cx - radius, cy - arm, cx + radius, cy + arm, 14, 14, paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * 0.38f);
        canvas.drawText("▲", cx, cy - radius * 0.48f, paint);
        canvas.drawText("▼", cx, cy + radius * 0.73f, paint);
        canvas.drawText("◀", cx - radius * 0.62f, cy + radius * 0.13f, paint);
        canvas.drawText("▶", cx + radius * 0.62f, cy + radius * 0.13f, paint);
    }

    private void drawButton(Canvas canvas, float cx, float cy, float radius, String label, int key,
            int alpha) {
        paint.setStyle(Paint.Style.FILL);
        boolean pressed = (visualKeys() & key) != 0;
        paint.setColor(pressed ? CONTROL_PRESSED_COLOR : CONTROL_COLOR);
        paint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, pressed ? radius * 0.94f : radius, paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * 0.72f);
        canvas.drawText(label, cx, cy + radius * 0.25f, paint);
    }

    private void drawPill(Canvas canvas, ControlLayout.Control control, int alpha) {
        float left = control.cx - control.halfWidth;
        float right = control.cx + control.halfWidth;
        float top = control.cy - control.halfHeight;
        float bottom = control.cy + control.halfHeight;
        paint.setColor((visualKeys() & control.key) == control.key
                ? CONTROL_PRESSED_COLOR : CONTROL_COLOR);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(left, top, right, bottom, control.halfHeight, control.halfHeight, paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setTextAlign(Paint.Align.CENTER);
        float textSize = control.halfHeight * 0.9f;
        paint.setTextSize(textSize);
        float labelWidth = paint.measureText(control.label);
        float maxLabelWidth = (right - left) * 0.82f;
        if (labelWidth > maxLabelWidth) {
            paint.setTextSize(textSize * maxLabelWidth / labelWidth);
        }
        canvas.drawText(control.label, control.cx, control.cy + control.halfHeight * 0.32f, paint);
    }

    private boolean controlsHidden(long nowMs) {
        return touchControlsHidden || FeelMath.shouldAutoHideControls(
                autoHideTouchControls, touchActive, nowMs, lastTouchMs,
                AUTO_HIDE_CONTROLS_MS);
    }

    private void clearTouchInput() {
        touchKeys = 0;
        touchTurboKeys = 0;
        previousTouchKeys = 0;
        touchActive = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long nowMs = SystemClock.uptimeMillis();
        boolean autoHidden = !touchControlsHidden && controlsHidden(nowMs);
        lastTouchMs = nowMs;

        if (action == MotionEvent.ACTION_DOWN) {
            touchActive = true;
            if (autoHidden && !layout.isMenuHit(event.getX(), event.getY())
                    && !layout.isMuteHit(event.getX(), event.getY())) {
                revealingHiddenControls = true;
                invalidate();
                return true;
            }
        }

        if (revealingHiddenControls) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                revealingHiddenControls = false;
                touchActive = false;
                postInvalidateDelayed(AUTO_HIDE_CONTROLS_MS);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            clearTouchInput();
            if (action == MotionEvent.ACTION_UP) {
                if (layout.isMenuHit(event.getX(), event.getY())) {
                    requestMenu.run();
                } else if (layout.isMuteHit(event.getX(), event.getY())) {
                    requestMute.run();
                } else if (!hasFrame) {
                    performClick();
                }
            }
            if (autoHideTouchControls) {
                postInvalidateDelayed(AUTO_HIDE_CONTROLS_MS);
            }
            return true;
        }

        int normalKeys = 0;
        int turboKeys = 0;
        if (!touchControlsHidden) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex()) {
                    continue;
                }
                ControlLayout.Input input = layout.inputAt(event.getX(i), event.getY(i));
                normalKeys |= input.normalKeys;
                turboKeys |= input.turboKeys;
            }
        }
        int allTouchKeys = normalKeys | turboKeys;
        if (!touchControlsHidden && hapticsEnabled
                && FeelMath.introducesNewPress(previousTouchKeys, allTouchKeys)) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        previousTouchKeys = allTouchKeys;
        touchKeys = normalKeys;
        touchTurboKeys = turboKeys;
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        requestRom.run();
        return true;
    }
}
