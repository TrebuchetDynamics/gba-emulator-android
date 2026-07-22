package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private static final int HOME = 0;
    private static final int VIDEO = 1;
    private static final int AUDIO = 2;
    private static final int CONTROLS = 3;
    private static final int STATES = 4;
    private static final int EMULATION = 5;

    private Settings settings;
    private LinearLayout content;
    private int section = HOME;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        setTitle(R.string.settings_title);
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xFF191C22);
        int pad = dp(20);
        content.setPadding(pad, dp(16), pad, dp(32));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF191C22);
        ScrollView.LayoutParams contentParams = new ScrollView.LayoutParams(
                Math.min(dp(520), getResources().getDisplayMetrics().widthPixels),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.START;
        scroll.addView(content, contentParams);
        setContentView(scroll);
        showHome();
    }

    private void showHome() {
        section = HOME;
        page(R.string.settings_title, R.string.settings_intro);
        content.addView(categoryRow(getString(R.string.settings_group_video),
                getString(R.string.settings_group_video_sub), v -> showSection(VIDEO)));
        content.addView(categoryRow(getString(R.string.settings_group_audio),
                getString(R.string.settings_group_audio_sub), v -> showSection(AUDIO)));
        content.addView(categoryRow(getString(R.string.settings_group_controls),
                getString(R.string.settings_group_controls_sub), v -> showSection(CONTROLS)));
        content.addView(categoryRow(getString(R.string.settings_group_states),
                getString(R.string.settings_group_states_sub), v -> showSection(STATES)));
        content.addView(categoryRow(getString(R.string.settings_group_emulation),
                getString(R.string.settings_group_emulation_sub), v -> showSection(EMULATION)));
    }

    private void showSection(int target) {
        section = target;
        switch (target) {
            case VIDEO:
                page(R.string.settings_group_video, R.string.settings_group_video_sub);
                content.addView(choiceRow(getString(R.string.settings_orientation),
                        orientationLabel(), v -> pickOrientation()));
                content.addView(choiceRow(getString(R.string.settings_scale),
                        scaleLabel(), v -> pickScale()));
                content.addView(switchRow(getString(R.string.settings_smooth_video),
                        getString(R.string.settings_smooth_video_sub), settings.smoothVideo(),
                        (b, on) -> settings.setSmoothVideo(on)));
                content.addView(choiceRow(getString(R.string.settings_dmg_palette),
                        settings.dmgPalette().label(), v -> pickDmgPalette()));
                break;
            case AUDIO:
                page(R.string.settings_group_audio, R.string.settings_group_audio_sub);
                content.addView(switchRow(getString(R.string.settings_audio_enabled),
                        settings.audioEnabled(), (b, on) -> settings.setAudioEnabled(on)));
                content.addView(sliderRow(getString(R.string.settings_volume), 0, 100,
                        settings.audioVolumePercent(), settings::setAudioVolumePercent));
                break;
            case CONTROLS:
                page(R.string.settings_group_controls, R.string.settings_group_controls_sub);
                content.addView(switchRow(getString(R.string.settings_haptics),
                        settings.haptics(), (b, on) -> settings.setHaptics(on)));
                content.addView(choiceRow(getString(R.string.settings_touch_visibility),
                        touchVisibilityLabel(), v -> pickTouchVisibility()));
                content.addView(sliderRow(getString(R.string.settings_active_opacity), 10, 100,
                        settings.activeControlOpacityPercent(),
                        settings::setActiveControlOpacityPercent));
                content.addView(sliderRow(getString(R.string.settings_opacity), 10, 100,
                        settings.controlOpacityPercent(), settings::setControlOpacityPercent));
                content.addView(choiceRow(getString(R.string.settings_gamepad),
                        getString(R.string.settings_gamepad_sub),
                        v -> startActivity(new android.content.Intent(
                                this, GamepadSettingsActivity.class))));
                break;
            case STATES:
                page(R.string.settings_group_states, R.string.settings_group_states_sub);
                content.addView(switchRow(getString(R.string.settings_auto_load_state),
                        getString(R.string.settings_auto_load_state_sub), settings.autoLoadState(),
                        (b, on) -> settings.setAutoLoadState(on)));
                content.addView(switchRow(getString(R.string.settings_confirm_reset),
                        getString(R.string.settings_confirm_reset_sub), settings.confirmReset(),
                        (b, on) -> settings.setConfirmReset(on)));
                break;
            case EMULATION:
                page(R.string.settings_group_emulation, R.string.settings_group_emulation_sub);
                content.addView(choiceRow(getString(R.string.settings_ff_speed),
                        settings.fastForwardSpeed() + "×", v -> pickFastForward()));
                content.addView(choiceRow(getString(R.string.settings_frameskip),
                        frameskipLabel(), v -> pickFrameskip()));
                break;
            default:
                showHome();
        }
    }

    private void page(int title, int intro) {
        content.removeAllViews();
        if (section != HOME) {
            TextView back = label("‹ " + getString(R.string.settings_title));
            back.setTextColor(0xFF9FB8E5);
            back.setTextSize(14);
            back.setPadding(0, 0, 0, dp(12));
            back.setClickable(true);
            back.setFocusable(true);
            back.setBackgroundResource(android.R.drawable.list_selector_background);
            back.setOnClickListener(v -> showHome());
            content.addView(back);
        }
        content.addView(pageTitle(getString(title)));
        content.addView(pageIntro(getString(intro)));
    }

    @Override
    public void onBackPressed() {
        if (section != HOME) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    private TextView pageTitle(String text) {
        TextView tv = label(text);
        tv.setTextSize(28);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private TextView pageIntro(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF9AA0AA);
        tv.setTextSize(14);
        tv.setPadding(0, dp(4), 0, dp(8));
        return tv;
    }

    private View switchRow(String label, boolean value,
                           android.widget.CompoundButton.OnCheckedChangeListener onChange) {
        return switchRow(label, null, value, onChange);
    }

    private View switchRow(String label, String detail, boolean value,
                           android.widget.CompoundButton.OnCheckedChangeListener onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(label(label));
        if (detail != null) {
            text.addView(detail(detail));
        }
        row.addView(text);

        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setContentDescription(label);
        sw.setOnCheckedChangeListener(onChange);
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return card(row);
    }

    private interface IntConsumer { void accept(int value); }

    private View sliderRow(String label, int min, int max, int value, IntConsumer onChange) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(10));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(label);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(name);
        TextView current = detail(value + "%");
        current.setTextColor(0xFFB6C9EC);
        heading.addView(current);
        col.addView(heading);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int selected = min + progress;
                current.setText(selected + "%");
                if (fromUser) {
                    onChange.accept(selected);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        col.addView(bar);
        return card(col);
    }

    private View categoryRow(String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(label(label));
        text.addView(detail(value));
        row.addView(text);

        TextView arrow = label("›");
        arrow.setTextColor(0xFF9FB8E5);
        arrow.setTextSize(26);
        row.addView(arrow);
        return card(row);
    }

    private View choiceRow(String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);
        row.addView(label(label));
        TextView sub = detail(value);
        sub.setTag("value");
        row.addView(sub);
        return card(row);
    }

    private View card(View view) {
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        view.setMinimumHeight(dp(60));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private TextView detail(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF9AA0AA);
        tv.setTextSize(13);
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        return tv;
    }

    private String touchVisibilityLabel() {
        switch (settings.touchVisibility()) {
            case AFTER_10_SECONDS:
                return getString(R.string.settings_touch_visibility_idle);
            case WITH_GAMEPAD:
                return getString(R.string.settings_touch_visibility_gamepad);
            default:
                return getString(R.string.settings_touch_visibility_always);
        }
    }

    private void pickTouchVisibility() {
        String[] labels = {
                getString(R.string.settings_touch_visibility_always),
                getString(R.string.settings_touch_visibility_idle),
                getString(R.string.settings_touch_visibility_gamepad) };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_touch_visibility)
                .setSingleChoiceItems(labels, settings.touchVisibility().ordinal(), (d, which) -> {
                    settings.setTouchVisibility(Settings.TouchVisibility.values()[which]);
                    d.dismiss();
                    showSection(CONTROLS);
                })
                .show();
    }

    private String orientationLabel() {
        switch (settings.orientation()) {
            case PORTRAIT: return getString(R.string.settings_orientation_portrait);
            case LANDSCAPE: return getString(R.string.settings_orientation_landscape);
            default: return getString(R.string.settings_orientation_auto);
        }
    }

    private String scaleLabel() {
        return settings.scaleMode() == Settings.ScaleMode.FILL
                ? getString(R.string.settings_scale_fill)
                : getString(R.string.settings_scale_integer);
    }

    private void pickOrientation() {
        String[] labels = {
                getString(R.string.settings_orientation_auto),
                getString(R.string.settings_orientation_portrait),
                getString(R.string.settings_orientation_landscape) };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_orientation)
                .setSingleChoiceItems(labels, settings.orientation().ordinal(), (d, which) -> {
                    settings.setOrientation(Settings.Orientation.values()[which]);
                    d.dismiss();
                    showSection(VIDEO);
                })
                .show();
    }

    private void pickScale() {
        String[] labels = {
                getString(R.string.settings_scale_integer),
                getString(R.string.settings_scale_fill) };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_scale)
                .setSingleChoiceItems(labels, settings.scaleMode().ordinal(), (d, which) -> {
                    settings.setScaleMode(Settings.ScaleMode.values()[which]);
                    d.dismiss();
                    showSection(VIDEO);
                })
                .show();
    }

    private void pickDmgPalette() {
        DmgPalette[] values = DmgPalette.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i].label();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_dmg_palette)
                .setSingleChoiceItems(labels, settings.dmgPalette().ordinal(), (d, which) -> {
                    settings.setDmgPalette(values[which]);
                    d.dismiss();
                    showSection(VIDEO);
                })
                .show();
    }

    private void pickFastForward() {
        String[] labels = {"2×", "3×", "4×"};
        int current = settings.fastForwardSpeed() - 2;
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_ff_speed)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    settings.setFastForwardSpeed(which + 2);
                    d.dismiss();
                    showSection(EMULATION);
                })
                .show();
    }

    private String frameskipLabel() {
        int f = settings.frameskip();
        return f == 0 ? getString(R.string.settings_frameskip_off) : String.valueOf(f);
    }

    private void pickFrameskip() {
        String[] labels = {getString(R.string.settings_frameskip_off), "1", "2", "3"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_frameskip)
                .setSingleChoiceItems(labels, settings.frameskip(), (d, which) -> {
                    settings.setFrameskip(which);
                    d.dismiss();
                    showSection(EMULATION);
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
