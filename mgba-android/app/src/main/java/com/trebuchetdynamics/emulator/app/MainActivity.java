package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private static final int OPEN_ROM = 100;
    private static final int MAX_ROM_BYTES = 32 * 1024 * 1024;
    private static final String STATE_ROM_URI = "romUri";

    private EmulatorView emulatorView;
    private EmulationRunner runner;
    private Uri romUri;
    private byte[] romData;
    private boolean resumed;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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

        emulatorView = new EmulatorView(this, this::openRomPicker);
        setContentView(emulatorView);
        emulatorView.requestFocus();

        if (state != null) {
            String savedUri = state.getString(STATE_ROM_URI);
            if (savedUri != null) {
                romUri = Uri.parse(savedUri);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (romData != null) {
            startRunner();
        } else if (romUri != null) {
            readRom(romUri);
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
        ++loadGeneration;
        stopRunner();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (romUri != null) {
            state.putString(STATE_ROM_URI, romUri.toString());
        }
    }

    private void openRomPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_ROM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_ROM || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri selected = data.getData();
        if (selected == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The temporary grant is sufficient for the immediate private-memory import.
        }
        romUri = selected;
        romData = null;
        readRom(selected);
    }

    private void readRom(Uri uri) {
        final int generation = ++loadGeneration;
        emulatorView.setStatus("Reading ROM…");
        new Thread(() -> {
            try {
                byte[] bytes = readBytes(uri);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) {
                        return;
                    }
                    romData = bytes;
                    if (resumed) {
                        startRunner();
                    }
                });
            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration) {
                        romUri = null;
                        emulatorView.setStatus("Could not read ROM — tap to retry");
                        Toast.makeText(this, "Could not read the selected ROM", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "rom-import").start();
    }

    private byte[] readBytes(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("Content provider returned no data");
            }
            byte[] buffer = new byte[64 * 1024];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_ROM_BYTES) {
                    throw new IOException("ROM exceeds the GBA cartridge limit");
                }
                output.write(buffer, 0, count);
            }
            if (total == 0) {
                throw new IOException("ROM is empty");
            }
            return output.toByteArray();
        }
    }

    private void startRunner() {
        if (!resumed || romData == null || runner != null) {
            return;
        }
        runner = new EmulationRunner(this, emulatorView, romData, message ->
                runOnUiThread(() -> {
                    runner = null;
                    romData = null;
                    romUri = null;
                    emulatorView.setStatus(message + " — tap to choose another ROM");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }));
        runner.start();
    }

    private void stopRunner() {
        if (runner != null) {
            EmulationRunner active = runner;
            runner = null;
            active.stop();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
