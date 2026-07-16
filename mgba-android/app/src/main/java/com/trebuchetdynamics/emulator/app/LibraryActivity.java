package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

public final class LibraryActivity extends Activity {
    private static final int OPEN_ROM = 200;

    private RomLibrary library;
    private LinearLayout listContainer;
    private TextView emptyView;
    private volatile boolean importing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = new RomLibrary(getFilesDir());
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1014);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.library_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        Button importButton = new Button(this);
        importButton.setText(R.string.library_import);
        importButton.setAllCaps(false);
        importButton.setOnClickListener(v -> openRomPicker());
        header.addView(importButton);
        root.addView(header);

        emptyView = new TextView(this);
        emptyView.setText(R.string.library_empty);
        emptyView.setTextColor(0xFF9AA0AA);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(48), 0, 0);
        root.addView(emptyView);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listContainer);
        root.addView(scroll);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        listContainer.removeAllViews();
        List<RomLibrary.Entry> entries = library.list();
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (RomLibrary.Entry entry : entries) {
            listContainer.addView(rowFor(entry));
        }
    }

    private View rowFor(RomLibrary.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        row.setPadding(p, p, p, p);
        row.setClickable(true);

        TextView name = new TextView(this);
        name.setText(entry.displayName);
        name.setTextColor(Color.WHITE);
        name.setTextSize(18);

        TextView sub = new TextView(this);
        sub.setText(entry.lastPlayedMs > 0
                ? DateUtils.getRelativeTimeSpanString(entry.lastPlayedMs)
                : getString(R.string.library_never));
        sub.setTextColor(0xFF9AA0AA);
        sub.setTextSize(13);

        row.addView(name);
        row.addView(sub);

        row.setOnClickListener(v -> play(entry.romId));
        row.setOnLongClickListener(v -> {
            confirmDelete(entry);
            return true;
        });
        return row;
    }

    private void play(String romId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ROM_ID, romId);
        startActivity(intent);
    }

    private void confirmDelete(RomLibrary.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.library_delete_title)
                .setMessage(getString(R.string.library_delete_message, entry.displayName))
                .setPositiveButton(R.string.library_delete_confirm, (d, w) -> {
                    try {
                        library.delete(entry.romId);
                    } catch (IOException ignored) {
                        // The files are best-effort removed; refresh reflects reality.
                    }
                    refresh();
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    private void openRomPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, OPEN_ROM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_ROM || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null || importing) {
            return;
        }
        importing = true;
        Toast.makeText(this, R.string.library_importing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                new RomImporter(this, library).importRom(uri);
                runOnUiThread(() -> {
                    importing = false;
                    refresh();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    importing = false;
                    Toast.makeText(this, R.string.library_import_failed, Toast.LENGTH_LONG).show();
                });
            }
        }, "rom-import").start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
