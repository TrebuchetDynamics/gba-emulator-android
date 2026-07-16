package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import java.io.File;
import java.io.IOException;

public final class MainActivity extends Activity {
    public static final String EXTRA_ROM_ID = "com.trebuchetdynamics.garnacha.ROM_ID";

    private EmulatorView emulatorView;
    private FrameLayout root;
    private InGameMenuView menu;
    private SaveStateStore states;
    private EmulationRunner runner;
    private RomLibrary library;
    private File romFile;
    private String romId;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = new RomLibrary(getFilesDir());
        String requestedRomId = getIntent().getStringExtra(EXTRA_ROM_ID);
        if (requestedRomId != null && library.exists(requestedRomId)) {
            romId = requestedRomId;
            romFile = new File(new File(getFilesDir(), "roms"), requestedRomId + ".gba");
            try {
                library.touch(requestedRomId, System.currentTimeMillis());
            } catch (IOException ignored) {
                // Non-fatal: last-played ordering is best-effort.
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF0E1014);
        getWindow().setNavigationBarColor(0xFF0E1014);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        emulatorView = new EmulatorView(this, this::openRomPicker, this::openNotices, this::openMenu);
        root = new FrameLayout(this);
        root.addView(emulatorView);
        menu = new InGameMenuView(this, new InGameMenuView.Listener() {
            @Override public void onSaveSlot(int slot) {
                if (runner != null) runner.postSaveState(slot);
            }
            @Override public void onLoadSlot(int slot) {
                if (runner != null) runner.postLoadState(slot);
            }
            @Override public void onToggleFastForward() {
                if (runner != null) {
                    runner.setFastForward(!runner.isFastForward());
                    refreshMenu();
                }
            }
            @Override public void onReset() {
                if (runner != null) runner.postReset();
                closeMenu();
            }
            @Override public void onSettings() {
                startActivity(new android.content.Intent(MainActivity.this, SettingsActivity.class));
                closeMenu();
            }
            @Override public void onClose() {
                closeMenu();
            }
        });
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        emulatorView.requestFocus();

        if (romFile == null) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (romFile != null && romFile.isFile()) {
            startRunner();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopRunner();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopRunner();
        super.onDestroy();
    }

    private void openNotices() {
        startActivity(new Intent(this, NoticesActivity.class));
    }

    private void openMenu() {
        if (runner == null || states == null) {
            return;
        }
        refreshMenu();
        menu.setVisibility(View.VISIBLE);
    }

    private void closeMenu() {
        menu.setVisibility(View.GONE);
    }

    private void refreshMenu() {
        if (states != null && runner != null) {
            menu.bind(states, runner.isFastForward());
        }
    }

    private void openRomPicker() {
        finish(); // return to the library to choose or import a ROM
    }

    private void startRunner() {
        if (!resumed || romFile == null || romId == null || runner != null) {
            return;
        }
        states = new SaveStateStore(new File(getFilesDir(), "states"), romId);
        runner = new EmulationRunner(this, emulatorView, romFile, romId, states,
                message -> runOnUiThread(() -> {
                    runner = null;
                    romFile = null;
                    romId = null;
                    closeMenu();
                    emulatorView.setStatus(message + " — tap to choose another ROM");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }),
                new EmulationRunner.StateListener() {
                    @Override public void onStateSaved(int slot) {
                        runOnUiThread(() -> {
                            refreshMenu();
                            Toast.makeText(MainActivity.this,
                                    "Saved to slot " + slot, Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onStateLoaded(int slot) {
                        runOnUiThread(() -> {
                            closeMenu();
                            Toast.makeText(MainActivity.this,
                                    "Loaded slot " + slot, Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onStateError(String message) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                message, Toast.LENGTH_SHORT).show());
                    }
                });
        runner.start();
    }

    private void stopRunner() {
        if (runner != null) {
            closeMenu();
            EmulationRunner active = runner;
            runner = null;
            active.stop();
        }
    }

    @Override
    public void onBackPressed() {
        if (menu.getVisibility() == View.VISIBLE) {
            closeMenu();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // While the menu overlay is open, let the view hierarchy handle keys
        // (gamepad navigation of the menu); do not drive the game underneath.
        if (menu != null && menu.getVisibility() == View.VISIBLE) {
            return super.dispatchKeyEvent(event);
        }
        int key = mapKey(event.getKeyCode());
        if (key != 0) {
            emulatorView.setHardwareKey(key, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_X:
                return MgbaSession.KEY_A;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_Z:
                return MgbaSession.KEY_B;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_ENTER:
                return MgbaSession.KEY_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_DEL:
                return MgbaSession.KEY_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP:
                return MgbaSession.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return MgbaSession.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return MgbaSession.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return MgbaSession.KEY_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return MgbaSession.KEY_L;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return MgbaSession.KEY_R;
            default:
                return 0;
        }
    }
}
