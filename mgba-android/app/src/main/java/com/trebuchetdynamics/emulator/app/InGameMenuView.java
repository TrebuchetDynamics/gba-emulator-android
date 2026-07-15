package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Translucent in-game menu drawn over the running game. Built in code. */
final class InGameMenuView extends LinearLayout {
    interface Listener {
        void onSaveSlot(int slot);
        void onLoadSlot(int slot);
        void onToggleFastForward();
        void onReset();
        void onClose();
    }

    private final Listener listener;
    private final Button fastForwardButton;
    private final Button[] saveButtons = new Button[SaveStateStore.SLOT_COUNT];
    private final Button[] loadButtons = new Button[SaveStateStore.SLOT_COUNT];

    InGameMenuView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xCC0E1014); // translucent scrim: game shows through
        // Swallow touches so they never reach the game behind the menu.
        setClickable(true);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);

        addView(sectionLabel(context.getString(R.string.menu_save)));
        addView(slotRow(context, saveButtons, true));
        addView(sectionLabel(context.getString(R.string.menu_load)));
        addView(slotRow(context, loadButtons, false));

        fastForwardButton = wideButton(context.getString(R.string.menu_fast_forward),
                v -> listener.onToggleFastForward());
        addView(fastForwardButton);
        addView(wideButton(context.getString(R.string.menu_reset), v -> listener.onReset()));
        Button settings = wideButton(context.getString(R.string.menu_settings), null);
        settings.setEnabled(false); // Phase 4 wires the settings screen.
        addView(settings);
        addView(wideButton(context.getString(R.string.menu_close), v -> listener.onClose()));
    }

    /** Refresh slot occupancy labels and the fast-forward toggle. */
    void bind(SaveStateStore store, boolean fastForward) {
        for (int i = 0; i < SaveStateStore.SLOT_COUNT; i++) {
            int slot = i + 1;
            String label = getContext().getString(
                    store.exists(slot) ? R.string.menu_slot_filled : R.string.menu_slot_empty,
                    slot);
            saveButtons[i].setText(String.valueOf(slot));
            loadButtons[i].setText(label);
            loadButtons[i].setEnabled(store.exists(slot));
        }
        fastForwardButton.setText(getContext().getString(R.string.menu_fast_forward)
                + (fastForward ? " ✓" : ""));
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private LinearLayout slotRow(Context context, Button[] into, boolean save) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < SaveStateStore.SLOT_COUNT; i++) {
            final int slot = i + 1;
            Button b = new Button(context);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                if (save) {
                    listener.onSaveSlot(slot);
                } else {
                    listener.onLoadSlot(slot);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(b, lp);
            into[i] = b;
        }
        return row;
    }

    private Button wideButton(String text, View.OnClickListener onClick) {
        Button b = new Button(getContext());
        b.setAllCaps(false);
        b.setText(text);
        if (onClick != null) {
            b.setOnClickListener(onClick);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(260), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
