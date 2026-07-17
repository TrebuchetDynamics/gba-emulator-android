package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
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
    private Settings settings;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        setTitle(R.string.settings_title);
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xFF0E1014);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        content.addView(header(getString(R.string.settings_group_video)));
        content.addView(choiceRow(getString(R.string.settings_orientation),
                orientationLabel(), v -> pickOrientation()));
        content.addView(choiceRow(getString(R.string.settings_scale),
                scaleLabel(), v -> pickScale()));

        content.addView(header(getString(R.string.settings_group_audio)));
        content.addView(switchRow(getString(R.string.settings_audio_enabled),
                settings.audioEnabled(), (b, on) -> settings.setAudioEnabled(on)));
        content.addView(sliderRow(getString(R.string.settings_volume), 0, 100,
                settings.audioVolumePercent(), settings::setAudioVolumePercent));

        content.addView(header(getString(R.string.settings_group_controls)));
        content.addView(switchRow(getString(R.string.settings_haptics),
                settings.haptics(), (b, on) -> settings.setHaptics(on)));
        content.addView(sliderRow(getString(R.string.settings_opacity), 10, 100,
                settings.controlOpacityPercent(), settings::setControlOpacityPercent));

        content.addView(header(getString(R.string.settings_group_emulation)));
        content.addView(choiceRow(getString(R.string.settings_ff_speed),
                settings.fastForwardSpeed() + "×", v -> pickFastForward()));
        content.addView(choiceRow(getString(R.string.settings_frameskip),
                frameskipLabel(), v -> pickFrameskip()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF7199DE);
        tv.setTextSize(14);
        tv.setPadding(0, dp(20), 0, dp(6));
        return tv;
    }

    private View switchRow(String label, boolean value,
                           android.widget.CompoundButton.OnCheckedChangeListener onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView tv = label(label);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(onChange);
        row.addView(sw);
        return row;
    }

    private interface IntConsumer { void accept(int value); }

    private View sliderRow(String label, int min, int max, int value, IntConsumer onChange) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(10), 0, dp(10));
        col.addView(label(label));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    onChange.accept(min + progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        col.addView(bar);
        return col;
    }

    private View choiceRow(String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        row.addView(label(label));
        TextView sub = new TextView(this);
        sub.setText(value);
        sub.setTextColor(0xFF9AA0AA);
        sub.setTextSize(13);
        sub.setTag("value");
        row.addView(sub);
        return row;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        return tv;
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
                    recreate();
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
                    recreate();
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
                    recreate();
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
                    recreate();
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
