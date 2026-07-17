package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

/**
 * WYSIWYG editor overlay for the on-screen control layout. Shown on top of the
 * running player; captures all touch so no input reaches the game. Edits the
 * current orientation's {@link ControlOverrides} and persists on Save.
 */
public final class LayoutEditorView extends FrameLayout {

    interface Callback {
        /** The editor finished (saved or cancelled); host should reload + hide. */
        void onLayoutEditorClosed();
    }

    private final Settings settings;
    private final Callback callback;
    private final Surface surface;
    private final SeekBar scaleBar;

    private ControlOverrides working = new ControlOverrides();
    private boolean landscape;
    private boolean hasSelection;
    private int selectedKey;
    private float selNormCx;
    private float selNormCy;
    private float selScale = 1f;

    LayoutEditorView(Context context, Settings settings, Callback callback) {
        super(context);
        this.settings = settings;
        this.callback = callback;

        surface = new Surface(context);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xCC0E1014);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);

        scaleBar = new SeekBar(context);
        scaleBar.setMax(100);
        scaleBar.setEnabled(false);
        scaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && hasSelection) {
                    selScale = LayoutEditMath.scaleForProgress(progress, sb.getMax());
                    working.put(selectedKey, selNormCx, selNormCy, selScale);
                    surface.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        bar.addView(scaleBar, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f));
        bar.addView(button(context, R.string.layout_reset, v -> {
            working.clear();
            hasSelection = false;
            scaleBar.setEnabled(false);
            surface.invalidate();
        }));
        bar.addView(button(context, R.string.layout_cancel, v -> callback.onLayoutEditorClosed()));
        bar.addView(button(context, R.string.layout_save, v -> {
            settings.setControlOverrides(landscape, working);
            callback.onLayoutEditorClosed();
        }));

        LayoutParams barParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        addView(bar, barParams);
    }

    /** Seed the editor from the saved overrides for the current orientation. */
    void begin(boolean landscape) {
        this.landscape = landscape;
        this.working = settings.controlOverrides(landscape);
        this.hasSelection = false;
        scaleBar.setEnabled(false);
        surface.invalidate();
    }

    private Button button(Context context, int textRes, View.OnClickListener onClick) {
        Button b = new Button(context);
        b.setText(textRes);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The drawing + drag surface. */
    private final class Surface extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Surface(Context context) {
            super(context);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(4f);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            ControlLayout layout = ControlLayout.of(w, h, working);
            text.setTextSize(Math.max(28, w * 0.03f));
            for (ControlLayout.Control c : layout.controls) {
                boolean sel = hasSelection && c.key == selectedKey;
                fill.setColor(sel ? 0x5533C1B3 : 0x33FFFFFF);
                stroke.setColor(sel ? 0xFF33C1B3 : 0x88FFFFFF);
                if (c.shape == ControlLayout.Shape.CIRCLE) {
                    canvas.drawCircle(c.cx, c.cy, c.halfWidth, fill);
                    canvas.drawCircle(c.cx, c.cy, c.halfWidth, stroke);
                } else {
                    canvas.drawRect(c.cx - c.halfWidth, c.cy - c.halfHeight,
                            c.cx + c.halfWidth, c.cy + c.halfHeight, fill);
                    canvas.drawRect(c.cx - c.halfWidth, c.cy - c.halfHeight,
                            c.cx + c.halfWidth, c.cy + c.halfHeight, stroke);
                }
                String label = c.shape == ControlLayout.Shape.DPAD ? "D-pad" : c.label;
                if (label != null && !label.isEmpty()) {
                    canvas.drawText(label, c.cx, c.cy + text.getTextSize() * 0.35f, text);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int w = getWidth();
            int h = getHeight();
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    ControlLayout layout = ControlLayout.of(w, h, working);
                    ControlLayout.Control hit = pick(layout, x, y);
                    if (hit == null) {
                        hasSelection = false;
                        scaleBar.setEnabled(false);
                    } else {
                        hasSelection = true;
                        selectedKey = hit.key;
                        selNormCx = hit.cx / w;
                        selNormCy = hit.cy / h;
                        selScale = working.has(hit.key) ? working.scale(hit.key) : 1f;
                        scaleBar.setEnabled(true);
                        scaleBar.setProgress(LayoutEditMath.progressForScale(selScale, scaleBar.getMax()));
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (hasSelection) {
                        selNormCx = LayoutEditMath.toNorm(x, w);
                        selNormCy = LayoutEditMath.toNorm(y, h);
                        working.put(selectedKey, selNormCx, selNormCy, selScale);
                        invalidate();
                    }
                    return true;
                }
                default:
                    return true;
            }
        }

        private ControlLayout.Control pick(ControlLayout layout, float x, float y) {
            ControlLayout.Control best = null;
            float bestArea = Float.MAX_VALUE;
            for (ControlLayout.Control c : layout.controls) {
                if (c.contains(x, y)) {
                    float area = c.halfWidth * c.halfHeight;
                    if (area < bestArea) {
                        bestArea = area;
                        best = c;
                    }
                }
            }
            return best;
        }
    }
}
