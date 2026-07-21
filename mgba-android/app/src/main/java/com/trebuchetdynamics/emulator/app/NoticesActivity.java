package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

public final class NoticesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.notices_title);

        TextView text = new TextView(this);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setTextColor(Color.WHITE);
        text.setTextIsSelectable(true);
        text.setGravity(Gravity.START);
        try (InputStream in = getAssets().open("NOTICES.md")) {
            text.setText(Notices.load(in));
        } catch (IOException e) {
            text.setText("Notices could not be loaded: " + e.getMessage());
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(14, 16, 20));
        ScrollView.LayoutParams textParams = new ScrollView.LayoutParams(
                Math.min(Math.round(680 * getResources().getDisplayMetrics().density),
                        getResources().getDisplayMetrics().widthPixels),
                ScrollView.LayoutParams.WRAP_CONTENT);
        textParams.gravity = Gravity.START;
        scroll.addView(text, textParams);
        setContentView(scroll);
    }
}
