package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    public static final String EXTRA_ROM_ID = "com.trebuchetdynamics.garnacha.ROM_ID";

    private EmulatorView emulatorView;
    private FrameLayout root;
    private InGameMenuView menu;
    private LayoutEditorView layoutEditor;
    private SaveStateStore states;
    private EmulationRunner runner;
    private RomLibrary library;
    private File romFile;
    private String romId;
    private boolean resumed;
    private boolean muted;
    private Settings settings;
    private KeyBindings bindings;
    private InputManager inputManager;
    private final InputManager.InputDeviceListener inputListener =
            new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { updateControllerMode(); }
                @Override public void onInputDeviceRemoved(int id) { updateControllerMode(); }
                @Override public void onInputDeviceChanged(int id) { updateControllerMode(); }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        bindings = settings.gamepadBindings(GamepadDefaults.map());
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        applyOrientation();
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

        emulatorView = new EmulatorView(
                this, this::openRomPicker, this::openMenu, this::toggleMute);
        root = new FrameLayout(this);
        root.addView(emulatorView);
        menu = new InGameMenuView(this, new InGameMenuView.Listener() {
            @Override public void onSaveSlot(int slot) {
                if (runner == null) {
                    return;
                }
                if (states == null || !states.exists(slot)) {
                    runner.postSaveState(slot);
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.save_overwrite_title)
                        .setMessage(getString(R.string.save_overwrite_message, slot))
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.save_overwrite_confirm,
                                (dialog, which) -> {
                                    if (runner != null) runner.postSaveState(slot);
                                })
                        .show();
            }
            @Override public void onLoadSlot(int slot) {
                if (runner != null) runner.postLoadState(slot);
            }
            @Override public void onLoadAutoState(int generation) {
                if (runner != null) runner.postLoadAutoState(generation);
            }
            @Override public void onToggleSlowMotion() {
                if (runner != null) {
                    runner.setSlowMotion(!runner.isSlowMotion());
                    emulatorView.showSpeedIndicator(runner.isSlowMotion()
                            ? getString(R.string.speed_slow_status) : "1×");
                    closeMenu();
                }
            }
            @Override public void onToggleFastForward() {
                if (runner != null) {
                    runner.setFastForward(!runner.isFastForward());
                    emulatorView.showSpeedIndicator(runner.isFastForward()
                            ? getString(R.string.speed_fast_status, settings.fastForwardSpeed())
                            : "1×");
                    closeMenu();
                }
            }
            @Override public void onRewind(int seconds) {
                if (runner != null) runner.postRewind(seconds);
            }
            @Override public void onScreenshot() {
                saveScreenshot();
            }
            @Override public void onReset() {
                closeMenu();
                if (!settings.confirmReset()) {
                    if (runner != null) runner.postReset();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.reset_confirm_title)
                        .setMessage(R.string.reset_confirm_message)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.menu_reset, (dialog, which) -> {
                            if (runner != null) runner.postReset();
                        })
                        .show();
            }
            @Override public void onLibrary() {
                closeMenu();
                openRomPicker();
            }
            @Override public void onSettings() {
                startActivity(new android.content.Intent(MainActivity.this, SettingsActivity.class));
                closeMenu();
            }
            @Override public void onNotices() {
                closeMenu();
                openNotices();
            }
            @Override public void onEditLayout() {
                menu.setVisibility(View.GONE);
                emulatorView.setTouchControlsHidden(true);
                boolean landscape = emulatorView.getWidth() > emulatorView.getHeight();
                layoutEditor.begin(landscape);
                layoutEditor.setVisibility(View.VISIBLE);
                layoutEditor.bringToFront();
            }
            @Override public void onClose() {
                closeMenu();
            }
        });
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        layoutEditor = new LayoutEditorView(this, settings, this::closeLayoutEditor);
        layoutEditor.setVisibility(View.GONE);
        root.addView(layoutEditor, new FrameLayout.LayoutParams(
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
        inputManager.registerInputDeviceListener(inputListener, null);
        applyOrientation();
        bindings = settings.gamepadBindings(GamepadDefaults.map());
        emulatorView.setHapticsEnabled(settings.haptics());
        emulatorView.setActiveOpacityAlpha(
                Settings.opacityPercentToAlpha(settings.activeControlOpacityPercent()));
        emulatorView.setIdleOpacityAlpha(
                Settings.opacityPercentToAlpha(settings.controlOpacityPercent()));
        emulatorView.setIntegerScale(settings.scaleMode() == Settings.ScaleMode.INTEGER);
        emulatorView.setSmoothVideo(settings.smoothVideo());
        updateControllerMode();
        emulatorView.setControlLayouts(
                settings.controlOverrides(false), settings.macroControls(false),
                settings.controlOverrides(true), settings.macroControls(true));
        if (romFile != null && romFile.isFile()) {
            startRunner();
        }
    }

    private void applyOrientation() {
        switch (settings.orientation()) {
            case PORTRAIT:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case LANDSCAPE:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        inputManager.unregisterInputDeviceListener(inputListener);
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

    private void toggleMute() {
        if (!settings.audioEnabled()) {
            emulatorView.showSpeedIndicator(getString(R.string.audio_disabled));
            emulatorView.announceForAccessibility(getString(R.string.audio_disabled));
            return;
        }
        muted = !muted;
        if (runner != null) {
            runner.setMuted(muted);
        }
        emulatorView.setMuted(muted);
        String status = getString(muted ? R.string.audio_muted : R.string.audio_on);
        emulatorView.showSpeedIndicator(status);
        emulatorView.announceForAccessibility(status);
    }

    /** Closes the layout editor without persisting any working (unsaved) edits. */
    private void closeLayoutEditor() {
        layoutEditor.setVisibility(View.GONE);
        emulatorView.setControlLayouts(
                settings.controlOverrides(false), settings.macroControls(false),
                settings.controlOverrides(true), settings.macroControls(true));
        updateControllerMode();
    }

    private void refreshMenu() {
        if (states != null && runner != null) {
            menu.bind(states, runner.isSlowMotion(), runner.isFastForward(),
                    settings.fastForwardSpeed(), runner.rewindSecondsAvailable());
        }
    }

    private void saveScreenshot() {
        closeMenu();
        final Bitmap screenshot;
        try {
            screenshot = emulatorView.copyGameFrame();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (screenshot == null) {
            Toast.makeText(this, R.string.screenshot_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            Uri pending = null;
            try {
                String name = "garnacha-" + System.currentTimeMillis() + ".png";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Garnacha Boy");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    pending = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (pending == null) throw new IOException("MediaStore rejected screenshot");
                    try (OutputStream out = getContentResolver().openOutputStream(pending)) {
                        if (out == null || !screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw new IOException("Could not encode screenshot");
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(pending, values, null, null);
                } else {
                    File root = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    if (root == null) throw new IOException("Pictures directory unavailable");
                    File directory = new File(root, "Screenshots");
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Could not create screenshot directory");
                    }
                    try (OutputStream out = new FileOutputStream(new File(directory, name))) {
                        if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw new IOException("Could not encode screenshot");
                        }
                    }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        R.string.screenshot_saved, Toast.LENGTH_SHORT).show());
            } catch (IOException | RuntimeException e) {
                if (pending != null) getContentResolver().delete(pending, null, null);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        R.string.screenshot_failed, Toast.LENGTH_SHORT).show());
            } finally {
                screenshot.recycle();
            }
        }, "screenshot-save").start();
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
                    @Override public void onAutoStateLoaded() {
                        runOnUiThread(() -> {
                            closeMenu();
                            Toast.makeText(MainActivity.this,
                                    R.string.autosave_resumed, Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onRewound(int seconds) {
                        runOnUiThread(() -> {
                            closeMenu();
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.rewind_complete, seconds),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onStateError(String message) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                message, Toast.LENGTH_SHORT).show());
                    }
                },
                settings.audioEnabled(),
                settings.audioVolumePercent() / 100f,
                settings.fastForwardSpeed(), settings.frameskip(),
                settings.dmgPalette().shades(), settings.autoLoadState());
        runner.setMuted(muted);
        emulatorView.setMuted(muted);
        runner.start();
        if (settings.autoLoadState() && states.hasAutoState()) {
            runner.postLoadAutoState();
        }
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
        if (layoutEditor != null && layoutEditor.getVisibility() == View.VISIBLE) {
            closeLayoutEditor();
            return;
        }
        if (menu.getVisibility() == View.VISIBLE) {
            if (!menu.handleBack()) {
                closeMenu();
            }
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
        int key = bindings == null ? 0 : bindings.gbaKeyFor(event.getKeyCode());
        if (key != 0) {
            if (settings.hideTouchWithGamepad()
                    && (event.getSource() & (InputDevice.SOURCE_GAMEPAD
                            | InputDevice.SOURCE_JOYSTICK)) != 0) {
                emulatorView.setTouchControlsHidden(true);
            }
            emulatorView.setHardwareKey(key, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void updateControllerMode() {
        if (emulatorView != null) {
            emulatorView.setTouchControlsHidden(settings.hideTouchWithGamepad() && hasGamepad());
        }
    }

    private static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && (device.getSources()
                    & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0) {
                return true;
            }
        }
        return false;
    }
}
