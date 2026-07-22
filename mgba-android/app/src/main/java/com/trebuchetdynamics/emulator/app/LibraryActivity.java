package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

public final class LibraryActivity extends Activity {
    private static final int OPEN_ROM = 200;
    private static final int BACKGROUND = 0xFF0E1014;
    private static final int SURFACE = 0xFF171B22;
    private static final int ACCENT = 0xFFB6C9EC;
    private static final int TEXT_SECONDARY = 0xFF9AA0AA;

    private RomLibrary library;
    private LinearLayout listContainer;
    private LinearLayout sectionHeader;
    private TextView sectionCount;
    private TextView emptyView;
    private ScrollView scroll;
    private Button importButton;
    private volatile boolean importing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = new RomLibrary(getFilesDir());
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this);
        title.setText(R.string.library_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.library_subtitle);
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setTextSize(13);
        brand.addView(subtitle);
        header.addView(brand);
        Button settingsButton = new Button(this);
        settingsButton.setText(R.string.settings_open);
        settingsButton.setAllCaps(false);
        settingsButton.setTextColor(ACCENT);
        settingsButton.setMinHeight(dp(48));
        settingsButton.setBackgroundResource(android.R.drawable.list_selector_background);
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settingsButton);
        root.addView(header);

        importButton = new Button(this);
        importButton.setText(R.string.library_import);
        importButton.setAllCaps(false);
        importButton.setTextColor(BACKGROUND);
        importButton.setTextSize(16);
        importButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        importButton.setMinHeight(dp(52));
        importButton.setBackground(rippleBackground(ACCENT, 0x337195C9, 12));
        importButton.setOnClickListener(v -> openRomPicker());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importParams.setMargins(0, dp(18), 0, dp(22));
        root.addView(importButton, importParams);

        sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView gamesTitle = new TextView(this);
        gamesTitle.setText(R.string.library_games);
        gamesTitle.setTextColor(Color.WHITE);
        gamesTitle.setTextSize(18);
        gamesTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        gamesTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sectionHeader.addView(gamesTitle);
        sectionCount = new TextView(this);
        sectionCount.setTextColor(TEXT_SECONDARY);
        sectionCount.setTextSize(13);
        sectionHeader.addView(sectionCount);
        root.addView(sectionHeader);

        emptyView = new TextView(this);
        emptyView.setText(R.string.library_empty);
        emptyView.setTextColor(TEXT_SECONDARY);
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setLineSpacing(0, 1.25f);
        emptyView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(emptyView);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(10), 0, dp(16));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVisibility(View.GONE);
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout outer = new FrameLayout(this);
        outer.setBackgroundColor(BACKGROUND);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(720)),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        outer.addView(root, contentParams);
        setContentView(outer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        listContainer.removeAllViews();
        List<RomLibrary.Entry> entries = library.list();
        boolean empty = entries.isEmpty();
        sectionHeader.setVisibility(empty ? View.GONE : View.VISIBLE);
        sectionCount.setText(getResources().getQuantityString(
                R.plurals.library_game_count, entries.size(), entries.size()));
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        scroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        for (RomLibrary.Entry entry : entries) {
            listContainer.addView(rowFor(entry));
        }
    }

    private View rowFor(RomLibrary.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(6), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(dp(80));
        row.setBackground(rippleBackground(SURFACE, 0x335F86C9, 14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowParams);

        TextView system = new TextView(this);
        system.setText(entry.system.badge());
        system.setTextColor(ACCENT);
        system.setTextSize(12);
        system.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        system.setGravity(Gravity.CENTER);
        system.setBackground(roundedBackground(0xFF222B39, 10));
        row.addView(system, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.setMargins(dp(12), 0, dp(4), 0);
        row.addView(copy, copyParams);

        TextView name = new TextView(this);
        name.setText(entry.displayName);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(name);

        TextView sub = new TextView(this);
        sub.setText(entry.lastPlayedMs > 0
                ? DateUtils.getRelativeTimeSpanString(entry.lastPlayedMs)
                : getString(R.string.library_never));
        sub.setTextColor(TEXT_SECONDARY);
        sub.setTextSize(13);
        copy.addView(sub);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(ACCENT);
        arrow.setTextSize(28);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(48)));

        TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextColor(TEXT_SECONDARY);
        more.setTextSize(24);
        more.setGravity(Gravity.CENTER);
        more.setClickable(true);
        more.setFocusable(true);
        more.setBackgroundResource(android.R.drawable.list_selector_background);
        more.setContentDescription(getString(R.string.library_more, entry.displayName));
        more.setOnClickListener(v -> showGameOptions(v, entry));
        row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));

        row.setContentDescription(entry.displayName + " · " + entry.system.badge());
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

    private void showGameOptions(View anchor, RomLibrary.Entry entry) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.library_play).setOnMenuItemClickListener(item -> {
            play(entry.romId);
            return true;
        });
        menu.getMenu().add(R.string.library_delete_confirm).setOnMenuItemClickListener(item -> {
            confirmDelete(entry);
            return true;
        });
        menu.show();
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
        setImporting(true);
        Toast.makeText(this, R.string.library_importing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                new RomImporter(this, library).importRom(uri);
                runOnUiThread(() -> {
                    setImporting(false);
                    refresh();
                });
            } catch (IOException | RuntimeException e) {
                String detail = e instanceof IOException ? e.getMessage() : null;
                runOnUiThread(() -> {
                    setImporting(false);
                    String message = detail == null || detail.trim().isEmpty()
                            ? getString(R.string.library_import_failed) : detail;
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.library_import_error_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        }, "rom-import").start();
    }

    private void setImporting(boolean value) {
        importing = value;
        importButton.setEnabled(!value);
        importButton.setAlpha(value ? 0.45f : 1f);
    }

    private Drawable roundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private Drawable rippleBackground(int color, int rippleColor, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                roundedBackground(color, radiusDp), mask);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
