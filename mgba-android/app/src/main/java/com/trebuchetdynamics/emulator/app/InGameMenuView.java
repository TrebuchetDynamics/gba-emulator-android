package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Thumb-anchored in-game menu over the running game. */
final class InGameMenuView extends FrameLayout {
    interface Listener {
        void onSaveSlot(int slot);
        void onLoadSlot(int slot);
        void onLoadAutoState(int generation);
        void onToggleSlowMotion();
        void onToggleFastForward();
        void onRewind(int seconds);
        void onScreenshot();
        void onReset();
        void onLibrary();
        void onSettings();
        void onNotices();
        void onEditLayout();
        void onClose();
    }

    private static final int[] REWIND_CHOICES = {1, 3, 5};
    private static final int[] RECOVERY_LABELS = {
            R.string.menu_recovery_latest,
            R.string.menu_recovery_previous,
            R.string.menu_recovery_oldest };

    private final Listener listener;
    private final LinearLayout panel;
    private final LinearLayout mainPage;
    private final LinearLayout morePage;
    private final LinearLayout rewindPage;
    private final LinearLayout recoveryPage;
    private final Button recoveryButton;
    private final Button slowMotionButton;
    private final Button fastForwardButton;
    private final Button rewindButton;
    private final Button[] rewindChoiceButtons = new Button[REWIND_CHOICES.length];
    private final Button[] recoveryButtons = new Button[SaveStateStore.AUTO_SLOT_COUNT];
    private final Button[] saveButtons = new Button[SaveStateStore.SLOT_COUNT];
    private final Button[] loadButtons = new Button[SaveStateStore.SLOT_COUNT];

    InGameMenuView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(0x40000000);
        setClickable(true);
        setOnClickListener(v -> listener.onClose());

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout anchor = new LinearLayout(context);
        anchor.setOrientation(LinearLayout.VERTICAL);
        anchor.setGravity(Gravity.BOTTOM);
        scroll.addView(anchor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // Swallow touches so they never reach the scrim or game.
        int pad = dp(12);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFA25282E);
        float corner = dp(12);
        background.setCornerRadii(new float[] {
                0, 0, corner, corner, corner, corner, 0, 0 });
        panel.setBackground(background);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        anchor.addView(panel, panelParams);

        int panelWidth = Math.min(dp(240),
                getResources().getDisplayMetrics().widthPixels - dp(16));
        LayoutParams scrollParams = new LayoutParams(panelWidth, LayoutParams.MATCH_PARENT,
                Gravity.START);
        addView(scroll, scrollParams);

        panel.addView(title(context.getString(R.string.menu_title)));
        mainPage = new LinearLayout(context);
        mainPage.setOrientation(LinearLayout.VERTICAL);
        panel.addView(mainPage);
        morePage = new LinearLayout(context);
        morePage.setOrientation(LinearLayout.VERTICAL);
        panel.addView(morePage);
        rewindPage = new LinearLayout(context);
        rewindPage.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rewindPage);
        recoveryPage = new LinearLayout(context);
        recoveryPage.setOrientation(LinearLayout.VERTICAL);
        panel.addView(recoveryPage);

        mainPage.addView(wideButton(
                context.getString(R.string.menu_close), v -> listener.onClose()));
        slowMotionButton = wideButton(context.getString(R.string.menu_slow_motion),
                v -> listener.onToggleSlowMotion());
        mainPage.addView(slowMotionButton);
        fastForwardButton = wideButton(context.getString(R.string.menu_fast_forward),
                v -> listener.onToggleFastForward());
        mainPage.addView(fastForwardButton);

        mainPage.addView(sectionLabel(context.getString(R.string.menu_save)));
        mainPage.addView(slotRow(context, saveButtons, true));
        mainPage.addView(sectionLabel(context.getString(R.string.menu_load)));
        mainPage.addView(slotRow(context, loadButtons, false));
        rewindButton = wideButton(
                context.getString(R.string.menu_rewind), v -> showRewindPage());
        mainPage.addView(rewindButton);
        mainPage.addView(wideButton(
                context.getString(R.string.menu_edit_layout), v -> listener.onEditLayout()));
        mainPage.addView(wideButton(context.getString(R.string.menu_more), v -> showMorePage()));

        morePage.addView(wideButton(context.getString(R.string.menu_back), v -> showMainPage()));
        recoveryButton = wideButton(
                context.getString(R.string.menu_recovery), v -> showRecoveryPage());
        morePage.addView(recoveryButton);
        morePage.addView(wideButton(context.getString(R.string.menu_screenshot),
                v -> listener.onScreenshot()));
        morePage.addView(wideButton(context.getString(R.string.menu_reset), v -> listener.onReset()));
        morePage.addView(wideButton(
                context.getString(R.string.menu_library), v -> listener.onLibrary()));
        morePage.addView(wideButton(
                context.getString(R.string.menu_settings), v -> listener.onSettings()));
        morePage.addView(wideButton(
                context.getString(R.string.menu_notices), v -> listener.onNotices()));

        rewindPage.addView(wideButton(
                context.getString(R.string.menu_back), v -> showMainPage()));
        rewindPage.addView(sectionLabel(context.getString(R.string.menu_rewind)));
        for (int i = 0; i < REWIND_CHOICES.length; i++) {
            int seconds = REWIND_CHOICES[i];
            Button choice = wideButton(getResources().getQuantityString(
                    R.plurals.menu_rewind_duration, seconds, seconds),
                    v -> listener.onRewind(seconds));
            rewindChoiceButtons[i] = choice;
            rewindPage.addView(choice);
        }

        recoveryPage.addView(wideButton(
                context.getString(R.string.menu_back_more), v -> showMorePage()));
        recoveryPage.addView(sectionLabel(context.getString(R.string.menu_recovery)));
        for (int i = 0; i < recoveryButtons.length; i++) {
            int generation = i + 1;
            Button recovery = wideButton(
                    context.getString(RECOVERY_LABELS[i]),
                    v -> listener.onLoadAutoState(generation));
            recoveryButtons[i] = recovery;
            recoveryPage.addView(recovery);
        }
        showMainPage();
    }

    /** Refresh slot occupancy labels and speed toggles. */
    void bind(SaveStateStore store, boolean slowMotion, boolean fastForward,
              int fastForwardSpeed, int rewindSecondsAvailable) {
        showMainPage();
        for (int i = 0; i < SaveStateStore.SLOT_COUNT; i++) {
            int slot = i + 1;
            boolean occupied = store.exists(slot);
            String label = getContext().getString(
                    occupied ? R.string.menu_slot_filled : R.string.menu_slot_empty, slot);
            saveButtons[i].setText(occupied ? slot + " •" : String.valueOf(slot));
            saveButtons[i].setContentDescription(
                    getContext().getString(R.string.menu_save) + " · " + label);
            loadButtons[i].setText(occupied
                    ? slot + "\n" + DateUtils.getRelativeTimeSpanString(store.timestamp(slot),
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE)
                    : "○ " + slot);
            loadButtons[i].setContentDescription(label);
            loadButtons[i].setEnabled(occupied);
            loadButtons[i].setAlpha(occupied ? 1f : 0.38f);
        }
        boolean hasRecovery = false;
        for (int i = 0; i < recoveryButtons.length; i++) {
            int generation = i + 1;
            boolean occupied = store.autoExists(generation);
            hasRecovery |= occupied;
            String name = getContext().getString(RECOVERY_LABELS[i]);
            String detail = occupied
                    ? DateUtils.getRelativeTimeSpanString(store.autoTimestamp(generation),
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                    : getContext().getString(R.string.menu_recovery_unavailable);
            recoveryButtons[i].setText(name + " · " + detail);
            recoveryButtons[i].setContentDescription(name + ", " + detail);
            recoveryButtons[i].setEnabled(occupied);
            recoveryButtons[i].setAlpha(occupied ? 1f : 0.38f);
        }
        recoveryButton.setEnabled(hasRecovery);
        recoveryButton.setAlpha(hasRecovery ? 1f : 0.38f);
        slowMotionButton.setText(getContext().getString(R.string.menu_slow_motion)
                + (slowMotion ? " ✓" : ""));
        fastForwardButton.setText(getContext().getString(
                R.string.menu_fast_forward_speed, fastForwardSpeed)
                + (fastForward ? " ✓" : ""));
        boolean canRewind = rewindSecondsAvailable > 0;
        rewindButton.setEnabled(canRewind);
        rewindButton.setAlpha(canRewind ? 1f : 0.38f);
        for (int i = 0; i < REWIND_CHOICES.length; i++) {
            boolean available = rewindSecondsAvailable >= REWIND_CHOICES[i];
            rewindChoiceButtons[i].setEnabled(available);
            rewindChoiceButtons[i].setAlpha(available ? 1f : 0.38f);
        }
    }

    /** Returns true when Back navigated within the drawer instead of closing it. */
    boolean handleBack() {
        if (recoveryPage.getVisibility() == View.VISIBLE) {
            showMorePage();
            return true;
        }
        if (morePage.getVisibility() == View.VISIBLE
                || rewindPage.getVisibility() == View.VISIBLE) {
            showMainPage();
            return true;
        }
        return false;
    }

    private void showMainPage() {
        mainPage.setVisibility(View.VISIBLE);
        morePage.setVisibility(View.GONE);
        rewindPage.setVisibility(View.GONE);
        recoveryPage.setVisibility(View.GONE);
    }

    private void showMorePage() {
        mainPage.setVisibility(View.GONE);
        morePage.setVisibility(View.VISIBLE);
        rewindPage.setVisibility(View.GONE);
        recoveryPage.setVisibility(View.GONE);
    }

    private void showRewindPage() {
        mainPage.setVisibility(View.GONE);
        morePage.setVisibility(View.GONE);
        rewindPage.setVisibility(View.VISIBLE);
        recoveryPage.setVisibility(View.GONE);
    }

    private void showRecoveryPage() {
        mainPage.setVisibility(View.GONE);
        morePage.setVisibility(View.GONE);
        rewindPage.setVisibility(View.GONE);
        recoveryPage.setVisibility(View.VISIBLE);
    }

    private TextView title(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFFB9BEC8);
        tv.setTextSize(16);
        tv.setPadding(dp(12), dp(4), dp(12), dp(8));
        return tv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFF8FA9D6);
        tv.setTextSize(12);
        tv.setPadding(dp(12), dp(12), dp(12), dp(2));
        return tv;
    }

    private LinearLayout slotRow(Context context, Button[] into, boolean save) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SaveStateStore.SLOT_COUNT; i++) {
            final int slot = i + 1;
            Button b = new Button(context);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setTextSize(save ? 15 : 11);
            b.setMaxLines(2);
            b.setMinHeight(dp(52));
            b.setPadding(0, 0, 0, 0);
            b.setBackgroundResource(android.R.drawable.list_selector_background);
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
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(52));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
