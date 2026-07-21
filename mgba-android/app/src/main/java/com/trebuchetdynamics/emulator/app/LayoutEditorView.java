package com.trebuchetdynamics.emulator.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

/**
 * WYSIWYG editor over the running player. Drag controls directly, then use the
 * bottom action bar for size, opacity, reset, and save.
 */
public final class LayoutEditorView extends FrameLayout {

    interface Callback {
        /** The editor finished (saved or cancelled); host should reload + hide. */
        void onLayoutEditorClosed();
    }

    private final Settings settings;
    private final Callback callback;
    private final Surface surface;
    private final TextView status;
    private final Button smallerButton;
    private final Button largerButton;
    private final Button addButton;
    private final Button deleteButton;

    private ControlOverrides working = new ControlOverrides();
    private MacroControls workingMacros = new MacroControls();
    private int workingOpacity;
    private boolean landscape;
    private boolean hasSelection;
    private int selectedControlId;
    private String selectedLabel;
    private float selNormCx;
    private float selNormCy;
    private float selScale = 1f;

    LayoutEditorView(Context context, Settings settings, Callback callback) {
        super(context);
        this.settings = settings;
        this.callback = callback;

        surface = new Surface(context);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        status = new TextView(context);
        status.setText(R.string.layout_editor_hint);
        status.setTextColor(0xFFB9BEC8);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(1);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));

        LinearLayout contextRow = new LinearLayout(context);
        contextRow.setGravity(Gravity.CENTER_VERTICAL);
        contextRow.setBackgroundColor(0xFA191C22);
        status.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        contextRow.addView(status);
        addButton = contextButton(R.string.layout_add_macro,
                R.string.layout_add_macro_description, v -> showAddMacroDialog());
        deleteButton = contextButton(R.string.layout_delete_macro,
                R.string.layout_delete_macro_description, v -> deleteSelectedMacro());
        deleteButton.setEnabled(false);
        contextRow.addView(addButton);
        contextRow.addView(deleteButton);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xF20E1014);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(4);
        bar.setPadding(pad, pad, pad, pad);

        bar.addView(actionButton(context, "✓", R.string.layout_save, v -> {
            settings.setControlLayout(landscape, working, workingMacros, workingOpacity);
            callback.onLayoutEditorClosed();
        }));
        smallerButton = actionButton(context, "−", R.string.layout_smaller,
                v -> adjustSelectedScale(-1));
        largerButton = actionButton(context, "+", R.string.layout_larger,
                v -> adjustSelectedScale(1));
        bar.addView(smallerButton);
        bar.addView(largerButton);
        bar.addView(actionButton(
                context, "α−", R.string.layout_fade_less, v -> adjustOpacity(-10)));
        bar.addView(actionButton(
                context, "α+", R.string.layout_fade_more, v -> adjustOpacity(10)));
        bar.addView(actionButton(context, "↺", R.string.layout_reset, v -> {
            working.clear();
            workingMacros.clear();
            hasSelection = false;
            setSizeActionsEnabled(false);
            deleteButton.setEnabled(false);
            updateAddButton();
            updateStatus();
            surface.invalidate();
        }));

        setSizeActionsEnabled(false);
        LinearLayout dock = new LinearLayout(context);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.addView(contextRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        dock.addView(bar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams dockParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        dockParams.gravity = Gravity.BOTTOM;
        addView(dock, dockParams);
    }

    /** Seed the editor from saved values for the current orientation. */
    void begin(boolean landscape) {
        this.landscape = landscape;
        working = settings.controlOverrides(landscape);
        workingMacros = settings.macroControls(landscape).copy();
        workingOpacity = settings.controlOpacityPercent();
        hasSelection = false;
        setSizeActionsEnabled(false);
        deleteButton.setEnabled(false);
        updateAddButton();
        updateStatus();
        surface.invalidate();
    }

    private void adjustSelectedScale(int direction) {
        if (!hasSelection) {
            return;
        }
        selScale = LayoutEditMath.stepScale(selScale, direction);
        working.put(selectedControlId, selNormCx, selNormCy, selScale);
        updateStatus();
        surface.invalidate();
    }

    private void adjustOpacity(int delta) {
        workingOpacity = Math.max(10, Math.min(100, workingOpacity + delta));
        updateStatus();
        surface.invalidate();
    }

    private void updateStatus() {
        status.setText(hasSelection
                ? getContext().getString(R.string.layout_editor_status,
                        selectedLabel, Math.round(selScale * 100f), workingOpacity)
                : getContext().getString(R.string.layout_editor_hint_with_opacity, workingOpacity));
    }

    private void setSizeActionsEnabled(boolean enabled) {
        smallerButton.setEnabled(enabled);
        largerButton.setEnabled(enabled);
    }

    private void updateAddButton() {
        boolean enabled = !workingMacros.isFull();
        addButton.setEnabled(enabled);
        addButton.setContentDescription(getContext().getString(enabled
                ? R.string.layout_add_macro_description : R.string.layout_macro_limit));
    }

    private ControlLayout editorLayout(int width, int height) {
        return ControlLayout.of(width, height, working,
                MgbaSession.VIDEO_WIDTH, MgbaSession.VIDEO_HEIGHT, true, workingMacros);
    }

    private void showAddMacroDialog() {
        if (workingMacros.isFull()) {
            status.setText(R.string.layout_macro_limit);
            status.announceForAccessibility(status.getText());
            return;
        }
        int[] keys = { MgbaSession.KEY_LEFT, MgbaSession.KEY_UP, MgbaSession.KEY_RIGHT,
                MgbaSession.KEY_DOWN, MgbaSession.KEY_A, MgbaSession.KEY_B,
                MgbaSession.KEY_L, MgbaSession.KEY_R, MgbaSession.KEY_SELECT,
                MgbaSession.KEY_START };
        String[] labels = { getContext().getString(R.string.input_left),
                getContext().getString(R.string.input_up),
                getContext().getString(R.string.input_right),
                getContext().getString(R.string.input_down), "A", "B", "L", "R",
                getContext().getString(R.string.input_select),
                getContext().getString(R.string.input_start) };

        ListView choices = new ListView(getContext());
        choices.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        choices.setAdapter(new ArrayAdapter<>(getContext(),
                android.R.layout.simple_list_item_multiple_choice, labels));
        CheckBox turbo = new CheckBox(getContext());
        turbo.setText(R.string.layout_macro_turbo);
        TextView error = new TextView(getContext());
        error.setTextColor(0xFFFF8A80);
        error.setVisibility(View.GONE);
        error.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(choices, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(220)));
        content.addView(turbo);
        content.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.layout_macro_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.layout_macro_add, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    int mask = 0;
                    for (int i = 0; i < keys.length; i++) {
                        if (choices.isItemChecked(i)) {
                            mask |= keys[i];
                        }
                    }
                    if (mask == 0) {
                        showMacroError(error, R.string.layout_macro_empty);
                        return;
                    }
                    if (workingMacros.containsDefinition(mask, turbo.isChecked())) {
                        showMacroError(error, R.string.layout_macro_duplicate);
                        return;
                    }
                    MacroControls.Macro macro = workingMacros.add(mask, turbo.isChecked());
                    selectedControlId = macro.layoutId();
                    selectedLabel = macro.contentLabel();
                    selNormCx = 0.5f;
                    selNormCy = 0.5f;
                    selScale = 1f;
                    working.put(selectedControlId, selNormCx, selNormCy, selScale);
                    hasSelection = true;
                    setSizeActionsEnabled(true);
                    deleteButton.setEnabled(true);
                    updateAddButton();
                    updateStatus();
                    surface.invalidate();
                    status.announceForAccessibility(status.getText());
                    if (workingMacros.isFull()) {
                        addButton.announceForAccessibility(
                                getContext().getString(R.string.layout_macro_limit));
                    }
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showMacroError(TextView error, int message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        error.announceForAccessibility(error.getText());
    }

    private void deleteSelectedMacro() {
        if (!hasSelection || !MacroControls.isMacroLayoutId(selectedControlId)) {
            return;
        }
        workingMacros.removeLayoutId(selectedControlId);
        working.remove(selectedControlId);
        hasSelection = false;
        setSizeActionsEnabled(false);
        deleteButton.setEnabled(false);
        updateAddButton();
        updateStatus();
        surface.invalidate();
    }

    private Button contextButton(int text, int description,
            View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setOnClickListener(listener);
        button.setContentDescription(getContext().getString(description));
        return button;
    }

    private Button actionButton(Context context, String symbol, int description,
            View.OnClickListener onClick) {
        Button b = new Button(context);
        b.setText(symbol);
        b.setContentDescription(context.getString(description));
        b.setTextColor(Color.WHITE);
        b.setTextSize(22);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(52));
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Drawing and drag surface. */
    private final class Surface extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Surface(Context context) {
            super(context);
            setContentDescription("Touch control layout preview. Tap and drag a control.");
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
            ControlLayout layout = editorLayout(w, h);
            int alpha = Settings.opacityPercentToAlpha(workingOpacity);
            float baseTextSize = Math.max(24, Math.min(w, h) * 0.03f);
            for (ControlLayout.Control c : layout.controls) {
                boolean selected = hasSelection && c.id == selectedControlId;
                int fillAlpha = selected ? Math.max(96, alpha) : Math.max(32, alpha * 2 / 3);
                int strokeAlpha = selected ? 255 : Math.max(96, alpha);
                fill.setColor(selected
                        ? Color.argb(fillAlpha, 51, 193, 179)
                        : Color.argb(fillAlpha, 255, 255, 255));
                stroke.setColor(selected
                        ? Color.rgb(51, 193, 179)
                        : Color.argb(strokeAlpha, 255, 255, 255));
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
                    text.setTextSize(baseTextSize);
                    float labelWidth = text.measureText(label);
                    float maxLabelWidth = c.halfWidth * 1.7f;
                    if (labelWidth > maxLabelWidth) {
                        text.setTextSize(baseTextSize * maxLabelWidth / labelWidth);
                    }
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
                    ControlLayout.Control hit = pick(editorLayout(w, h), x, y);
                    if (hit == null) {
                        hasSelection = false;
                        setSizeActionsEnabled(false);
                        deleteButton.setEnabled(false);
                    } else {
                        hasSelection = true;
                        selectedControlId = hit.id;
                        MacroControls.Macro macro =
                                workingMacros.macroForLayoutId(selectedControlId);
                        selectedLabel = macro == null
                                ? (hit.shape == ControlLayout.Shape.DPAD ? "D-pad" : hit.label)
                                : macro.contentLabel();
                        selNormCx = hit.cx / w;
                        selNormCy = hit.cy / h;
                        selScale = working.has(hit.id) ? working.scale(hit.id) : 1f;
                        setSizeActionsEnabled(true);
                        deleteButton.setEnabled(macro != null);
                    }
                    updateStatus();
                    if (hasSelection) {
                        status.announceForAccessibility(status.getText());
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (hasSelection) {
                        selNormCx = LayoutEditMath.toNorm(x, w);
                        selNormCy = LayoutEditMath.toNorm(y, h);
                        working.put(selectedControlId, selNormCx, selNormCy, selScale);
                        invalidate();
                    }
                    return true;
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
                    if (area <= bestArea) {
                        bestArea = area;
                        best = c;
                    }
                }
            }
            return best;
        }
    }
}
