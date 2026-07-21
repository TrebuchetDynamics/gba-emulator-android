package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

/** Press-to-bind editor for the physical-controller → GBA-button map. */
public final class GamepadSettingsActivity extends Activity {

    private static final int[] GBA_KEYS = {
            MgbaSession.KEY_UP, MgbaSession.KEY_DOWN, MgbaSession.KEY_LEFT, MgbaSession.KEY_RIGHT,
            MgbaSession.KEY_A, MgbaSession.KEY_B, MgbaSession.KEY_L, MgbaSession.KEY_R,
            MgbaSession.KEY_START, MgbaSession.KEY_SELECT };
    private static final String[] GBA_LABELS = {
            "Up", "Down", "Left", "Right", "A", "B", "L", "R", "START", "SELECT" };

    private Settings settings;
    private KeyBindings bindings;
    private LinearLayout list;
    private int pendingKeyCode = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        bindings = settings.gamepadBindings(GamepadDefaults.map());
        setTitle(R.string.gamepad_title);
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xFF191C22);
        int pad = dp(20);
        content.setPadding(pad, dp(16), pad, dp(32));

        TextView title = new TextView(this);
        title.setText(R.string.gamepad_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView intro = new TextView(this);
        intro.setText(R.string.gamepad_intro);
        intro.setTextColor(0xFF9AA0AA);
        intro.setTextSize(14);
        intro.setPadding(0, dp(4), 0, dp(8));
        content.addView(intro);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Button reset = new Button(this);
        reset.setText(R.string.gamepad_reset);
        reset.setAllCaps(false);
        reset.setTextColor(0xFFB6C9EC);
        reset.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        reset.setBackgroundResource(android.R.drawable.list_selector_background);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(16);
        reset.setLayoutParams(resetParams);
        reset.setOnClickListener(v -> {
            bindings.reset(GamepadDefaults.map());
            settings.setGamepadBindings(bindings);
            buildRows();
        });
        content.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF191C22);
        ScrollView.LayoutParams contentParams = new ScrollView.LayoutParams(
                Math.min(dp(520), getResources().getDisplayMetrics().widthPixels),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = android.view.Gravity.START;
        scroll.addView(content, contentParams);
        setContentView(scroll);
        buildRows();
    }

    private void buildRows() {
        list.removeAllViews();
        for (int i = 0; i < GBA_KEYS.length; i++) {
            int gbaKey = GBA_KEYS[i];
            String gbaLabel = GBA_LABELS[i];
            String bindingLabel = keyLabel(bindings.keyCodeFor(gbaKey));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setMinimumHeight(dp(60));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.setContentDescription(getString(
                    R.string.gamepad_mapping_description, gbaLabel, bindingLabel));
            row.setOnClickListener(v -> showBindingDialog(gbaKey, gbaLabel));

            TextView name = new TextView(this);
            name.setText(gbaLabel);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            row.addView(name);

            TextView sub = new TextView(this);
            sub.setText(getString(R.string.gamepad_assigned, bindingLabel));
            sub.setTextColor(0xFF9AA0AA);
            sub.setTextSize(13);
            row.addView(sub);
            list.addView(row);
        }
    }

    private String keyLabel(int keyCode) {
        if (keyCode < 0) {
            return getString(R.string.gamepad_unbound);
        }
        String s = KeyEvent.keyCodeToString(keyCode); // e.g. "KEYCODE_BUTTON_A"
        return s.startsWith("KEYCODE_") ? s.substring("KEYCODE_".length()) : s;
    }

    private void showBindingDialog(int gbaKey, String gbaLabel) {
        pendingKeyCode = -1;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(gbaLabel)
                .setMessage(getString(R.string.gamepad_press_for, gbaLabel))
                .setNegativeButton(R.string.gamepad_unset, (d, which) -> {
                    bindings.unbind(gbaKey);
                    settings.setGamepadBindings(bindings);
                })
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (pendingKeyCode >= 0) {
                        bindings.bind(gbaKey, pendingKeyCode);
                        settings.setGamepadBindings(bindings);
                    }
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setEnabled(false));
        dialog.setOnDismissListener(d -> {
            pendingKeyCode = -1;
            buildRows();
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK || isIgnoredKey(keyCode)) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                pendingKeyCode = keyCode;
                dialog.setMessage(getString(
                        R.string.gamepad_selected, keyLabel(keyCode)));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }
            return true;
        });
        dialog.show();
    }

    private static boolean isIgnoredKey(int kc) {
        return kc == KeyEvent.KEYCODE_HOME
                || kc == KeyEvent.KEYCODE_APP_SWITCH
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE
                || kc == KeyEvent.KEYCODE_POWER;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
