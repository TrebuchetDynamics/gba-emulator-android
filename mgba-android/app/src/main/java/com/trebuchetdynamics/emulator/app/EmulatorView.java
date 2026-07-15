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
    private final RectF frameDst = new RectF();
    private final Runnable requestRom;
    private final Runnable requestNotices;
    private final Runnable requestMenu;

    private ControlLayout layout = ControlLayout.of(1, 1);

    private volatile int touchKeys;
    private volatile int hardwareKeys;
    private volatile boolean hasFrame;
    private volatile String status = "Tap to load a GBA ROM";

    public EmulatorView(Context context) {
        this(context, () -> {}, () -> {}, () -> {});
    }

    EmulatorView(Context context, Runnable requestRom, Runnable requestNotices, Runnable requestMenu) {
        super(context);
        this.requestRom = requestRom;
        this.requestNotices = requestNotices;
        this.requestMenu = requestMenu;
        setBackgroundColor(Color.rgb(14, 16, 20));
        paint.setFilterBitmap(false); // crisp GBA pixels, no bilinear smoothing
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layout = ControlLayout.of(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        layout = ControlLayout.of(getWidth(), getHeight());
        gameRect.set(layout.gameLeft, layout.gameTop, layout.gameRight, layout.gameBottom);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(gameRect, 14, 14, paint); // letterbox backdrop
        if (hasFrame) {
            FeelMath.Box draw = FeelMath.integerScale(
                    gameRect.left, gameRect.top, gameRect.right, gameRect.bottom,
                    MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT);
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

        paint.setColor(0xCC262A31);
        canvas.drawRoundRect(layout.loadLeft, layout.loadTop, layout.loadRight, layout.loadBottom,
                12, 12, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        float loadHeight = layout.loadBottom - layout.loadTop;
        paint.setTextSize(loadHeight * 0.42f);
        canvas.drawText("LOAD", (layout.loadLeft + layout.loadRight) / 2,
                layout.loadTop + loadHeight * 0.66f, paint);

        paint.setColor(0xCC262A31);
        canvas.drawRoundRect(layout.noticesLeft, layout.noticesTop,
                layout.noticesRight, layout.noticesBottom, 12, 12, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        float noticesHeight = layout.noticesBottom - layout.noticesTop;
        paint.setTextSize(noticesHeight * 0.34f);
        canvas.drawText("NOTICES", (layout.noticesLeft + layout.noticesRight) / 2,
                layout.noticesTop + noticesHeight * 0.66f, paint);

        paint.setColor(0xCC262A31);
        canvas.drawRoundRect(layout.menuLeft, layout.menuTop,
                layout.menuRight, layout.menuBottom, 12, 12, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        float menuHeight = layout.menuBottom - layout.menuTop;
        paint.setTextSize(menuHeight * 0.42f);
        canvas.drawText("MENU", (layout.menuLeft + layout.menuRight) / 2,
                layout.menuTop + menuHeight * 0.66f, paint);

        for (ControlLayout.Control control : layout.controls) {
            switch (control.shape) {
                case DPAD:
                    drawDpad(canvas, control.cx, control.cy, control.halfWidth);
                    break;
                case CIRCLE:
                    drawButton(canvas, control.cx, control.cy, control.halfWidth,
                            control.label, control.key);
                    break;
                case PILL:
                    drawPill(canvas, control);
                    break;
            }
        }
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

    private void drawPill(Canvas canvas, ControlLayout.Control control) {
        float left = control.cx - control.halfWidth;
        float right = control.cx + control.halfWidth;
        float top = control.cy - control.halfHeight;
        float bottom = control.cy + control.halfHeight;
        paint.setColor((keys() & control.key) != 0 ? Color.rgb(113, 153, 222) : Color.rgb(62, 66, 74));
        canvas.drawRoundRect(left, top, right, bottom, control.halfHeight, control.halfHeight, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(control.halfHeight * 0.9f);
        canvas.drawText(control.label, control.cx, control.cy + control.halfHeight * 0.32f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            touchKeys = 0;
            if (layout.isMenuHit(event.getX(), event.getY())) {
                requestMenu.run();
            } else if (layout.isNoticesHit(event.getX(), event.getY())) {
                requestNotices.run();
            } else if (!hasFrame || layout.isLoadHit(event.getX(), event.getY())) {
                performClick();
            }
            return true;
        }

        int keys = 0;
        if (event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                keys |= layout.keysAt(event.getX(i), event.getY(i));
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
}
