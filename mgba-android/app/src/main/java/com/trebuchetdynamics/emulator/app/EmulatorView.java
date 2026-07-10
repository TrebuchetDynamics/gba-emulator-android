package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

final class EmulatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap frame = Bitmap.createBitmap(
            MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
    private final Object frameLock = new Object();
    private final RectF gameRect = new RectF();
    private final Runnable requestRom;

    private volatile int touchKeys;
    private volatile int hardwareKeys;
    private volatile boolean hasFrame;
    private volatile String status = "Tap to load a GBA ROM";

    public EmulatorView(Context context) {
        this(context, () -> {});
    }

    EmulatorView(Context context, Runnable requestRom) {
        super(context);
        this.requestRom = requestRom;
        setBackgroundColor(Color.rgb(14, 16, 20));
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    int keys() {
        return touchKeys | hardwareKeys;
    }

    void setHardwareKey(int key, boolean pressed) {
        if (pressed) {
            hardwareKeys |= key;
        } else {
            hardwareKeys &= ~key;
        }
        invalidate();
    }

    void setStatus(String value) {
        status = value;
        if (!"Running".equals(value)) {
            hasFrame = false;
        }
        postInvalidate();
    }

    void publishFrame(int[] pixels) {
        synchronized (frameLock) {
            frame.setPixels(pixels, 0, MgbaSession.VIDEO_WIDTH, 0, 0,
                    MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT);
        }
        hasFrame = true;
        status = "Running";
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float margin = width * 0.025f;
        float gameWidth = width - margin * 2;
        float gameHeight = gameWidth * MgbaSession.VIDEO_HEIGHT / MgbaSession.VIDEO_WIDTH;
        if (gameHeight > height * 0.5f) {
            gameHeight = height * 0.5f;
            gameWidth = gameHeight * MgbaSession.VIDEO_WIDTH / MgbaSession.VIDEO_HEIGHT;
        }
        float gameLeft = (width - gameWidth) / 2;
        float gameTop = margin;
        gameRect.set(gameLeft, gameTop, gameLeft + gameWidth, gameTop + gameHeight);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(gameRect, 14, 14, paint);
        if (hasFrame) {
            synchronized (frameLock) {
                canvas.drawBitmap(frame, null, gameRect, paint);
            }
        } else {
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(32, width * 0.045f));
            canvas.drawText(status, width / 2, gameRect.centerY(), paint);
        }
        paint.setColor(0xCC262A31);
        float loadWidth = width * 0.18f;
        float loadHeight = Math.max(54, width * 0.075f);
        canvas.drawRoundRect(gameRect.right - loadWidth - 12, gameRect.top + 12,
                gameRect.right - 12, gameRect.top + 12 + loadHeight, 12, 12, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(loadHeight * 0.42f);
        canvas.drawText("LOAD", gameRect.right - loadWidth / 2 - 12,
                gameRect.top + 12 + loadHeight * 0.66f, paint);

        float controlsTop = gameRect.bottom + margin;
        float controlsHeight = height - controlsTop;
        float shoulderY = controlsTop + controlsHeight * 0.10f;
        drawPill(canvas, width * 0.16f, shoulderY, width * 0.22f, "L", MgbaSession.KEY_L);
        drawPill(canvas, width * 0.84f, shoulderY, width * 0.22f, "R", MgbaSession.KEY_R);
        float dpadX = width * 0.24f;
        float dpadY = controlsTop + controlsHeight * 0.42f;
        float dpadRadius = Math.min(width, controlsHeight) * 0.17f;
        drawDpad(canvas, dpadX, dpadY, dpadRadius);

        float buttonRadius = Math.min(width, controlsHeight) * 0.09f;
        float buttonY = controlsTop + controlsHeight * 0.39f;
        drawButton(canvas, width * 0.80f, buttonY - buttonRadius * 0.45f,
                buttonRadius, "A", MgbaSession.KEY_A);
        drawButton(canvas, width * 0.65f, buttonY + buttonRadius * 0.45f,
                buttonRadius, "B", MgbaSession.KEY_B);

        float smallY = controlsTop + controlsHeight * 0.78f;
        drawPill(canvas, width * 0.39f, smallY, width * 0.15f, "SELECT",
                MgbaSession.KEY_SELECT);
        drawPill(canvas, width * 0.61f, smallY, width * 0.15f, "START",
                MgbaSession.KEY_START);
    }

    private void drawDpad(Canvas canvas, float cx, float cy, float radius) {
        paint.setColor(Color.rgb(62, 66, 74));
        paint.setStyle(Paint.Style.FILL);
        float arm = radius * 0.42f;
        canvas.drawRoundRect(cx - arm, cy - radius, cx + arm, cy + radius, 14, 14, paint);
        canvas.drawRoundRect(cx - radius, cy - arm, cx + radius, cy + arm, 14, 14, paint);
        paint.setColor(Color.rgb(120, 126, 138));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * 0.38f);
        canvas.drawText("▲", cx, cy - radius * 0.48f, paint);
        canvas.drawText("▼", cx, cy + radius * 0.73f, paint);
        canvas.drawText("◀", cx - radius * 0.62f, cy + radius * 0.13f, paint);
        canvas.drawText("▶", cx + radius * 0.62f, cy + radius * 0.13f, paint);
    }

    private void drawButton(Canvas canvas, float cx, float cy, float radius, String label, int key) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((keys() & key) != 0 ? Color.rgb(113, 153, 222) : Color.rgb(62, 66, 74));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * 0.72f);
        canvas.drawText(label, cx, cy + radius * 0.25f, paint);
    }

    private void drawPill(Canvas canvas, float cx, float cy, float width, String label, int key) {
        float pillHeight = width * 0.34f;
        paint.setColor((keys() & key) != 0 ? Color.rgb(113, 153, 222) : Color.rgb(62, 66, 74));
        canvas.drawRoundRect(cx - width / 2, cy - pillHeight / 2,
                cx + width / 2, cy + pillHeight / 2, pillHeight / 2, pillHeight / 2, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pillHeight * 0.45f);
        canvas.drawText(label, cx, cy + pillHeight * 0.16f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && (!hasFrame || isLoadHit(event.getX(), event.getY()))) {
            touchKeys = 0;
            performClick();
            return true;
        }

        int keys = 0;
        if (event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                keys |= keysAt(event.getX(i), event.getY(i));
            }
        }
        touchKeys = keys;
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        requestRom.run();
        return true;
    }

    private boolean isLoadHit(float x, float y) {
        float loadWidth = getWidth() * 0.18f;
        float loadHeight = Math.max(54, getWidth() * 0.075f);
        return x >= gameRect.right - loadWidth - 24 && x <= gameRect.right
                && y >= gameRect.top && y <= gameRect.top + loadHeight + 24;
    }

    private int keysAt(float x, float y) {
        float width = getWidth();
        float controlsTop = gameRect.bottom + width * 0.025f;
        float controlsHeight = getHeight() - controlsTop;
        float shoulderY = controlsTop + controlsHeight * 0.10f;
        int keys = 0;
        if (Math.abs(y - shoulderY) < width * 0.07f) {
            if (Math.abs(x - width * 0.16f) < width * 0.13f) keys |= MgbaSession.KEY_L;
            if (Math.abs(x - width * 0.84f) < width * 0.13f) keys |= MgbaSession.KEY_R;
        }

        float dpadX = width * 0.24f;
        float dpadY = controlsTop + controlsHeight * 0.42f;
        float dpadRadius = Math.min(width, controlsHeight) * 0.19f;
        float dx = x - dpadX;
        float dy = y - dpadY;
        if (Math.abs(dx) <= dpadRadius && Math.abs(dy) <= dpadRadius) {
            float dead = dpadRadius * 0.18f;
            if (dx < -dead) keys |= MgbaSession.KEY_LEFT;
            if (dx > dead) keys |= MgbaSession.KEY_RIGHT;
            if (dy < -dead) keys |= MgbaSession.KEY_UP;
            if (dy > dead) keys |= MgbaSession.KEY_DOWN;
        }

        float buttonRadius = Math.min(width, controlsHeight) * 0.12f;
        float buttonY = controlsTop + controlsHeight * 0.39f;
        if (distanceSquared(x, y, width * 0.80f, buttonY - buttonRadius * 0.34f)
                < buttonRadius * buttonRadius) {
            keys |= MgbaSession.KEY_A;
        }
        if (distanceSquared(x, y, width * 0.65f, buttonY + buttonRadius * 0.34f)
                < buttonRadius * buttonRadius) {
            keys |= MgbaSession.KEY_B;
        }

        float smallY = controlsTop + controlsHeight * 0.78f;
        if (Math.abs(y - smallY) < width * 0.06f) {
            if (Math.abs(x - width * 0.39f) < width * 0.10f) keys |= MgbaSession.KEY_SELECT;
            if (Math.abs(x - width * 0.61f) < width * 0.10f) keys |= MgbaSession.KEY_START;
        }
        return keys;
    }

    private static float distanceSquared(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}
