package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.graphics.Color;
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
            MgbaSession.KEY_A, MgbaSession.KEY_B, MgbaSession.KEY_L, MgbaSession.KEY_R,
            MgbaSession.KEY_START, MgbaSession.KEY_SELECT,
            MgbaSession.KEY_UP, MgbaSession.KEY_DOWN, MgbaSession.KEY_LEFT, MgbaSession.KEY_RIGHT };
    private static final String[] GBA_LABELS = {
            "A", "B", "L", "R", "START", "SELECT",
            "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right" };

    private Settings settings;
    private KeyBindings bindings;
    private LinearLayout list;
    private int listeningGbaKey; // 0 = not listening

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
        content.setBackgroundColor(0xFF0E1014);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Button reset = new Button(this);
        reset.setText(R.string.gamepad_reset);
        reset.setOnClickListener(v -> {
            bindings.reset(GamepadDefaults.map());
            settings.setGamepadBindings(bindings);
            listeningGbaKey = 0;
            buildRows();
        });
        content.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        buildRows();
    }

    private void buildRows() {
        list.removeAllViews();
        for (int i = 0; i < GBA_KEYS.length; i++) {
            final int gbaKey = GBA_KEYS[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                listeningGbaKey = gbaKey;
                buildRows();
            });

            TextView name = new TextView(this);
            name.setText(GBA_LABELS[i]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            row.addView(name);

            TextView sub = new TextView(this);
            sub.setTextColor(0xFF9AA0AA);
            sub.setTextSize(13);
            if (gbaKey == listeningGbaKey) {
                sub.setText(R.string.gamepad_press);
            } else {
                sub.setText(keyLabel(bindings.keyCodeFor(gbaKey)));
            }
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (listeningGbaKey == 0 || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK) {
            listeningGbaKey = 0; // cancel, do not leave the screen
            buildRows();
            return true;
        }
        if (isIgnoredKey(kc)) {
            return super.dispatchKeyEvent(event); // never bind system nav/volume/power
        }
        bindings.bind(listeningGbaKey, kc);
        settings.setGamepadBindings(bindings);
        listeningGbaKey = 0;
        buildRows();
        return true;
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
