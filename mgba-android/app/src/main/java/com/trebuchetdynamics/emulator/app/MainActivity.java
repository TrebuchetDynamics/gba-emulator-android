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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private static final int OPEN_ROM = 100;
    private static final int MAX_ROM_BYTES = 32 * 1024 * 1024;
    private static final String STATE_ROM_URI = "romUri";

    private EmulatorView emulatorView;
    private EmulationRunner runner;
    private Uri romUri;
    private File romFile;
    private String romId;
    private boolean resumed;
    private boolean importing;
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

        emulatorView = new EmulatorView(this, this::openRomPicker, this::openNotices);
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
        if (romFile != null && romFile.isFile()) {
            startRunner();
        } else if (romUri != null && !importing) {
            importRomAsync(romUri);
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

    private void openNotices() {
        startActivity(new Intent(this, NoticesActivity.class));
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
            // The temporary grant is sufficient for the immediate private-file import.
        }
        romUri = selected;
        romFile = null;
        romId = null;
        importRomAsync(selected);
    }

    private void importRomAsync(Uri uri) {
        final int generation = ++loadGeneration;
        importing = true;
        emulatorView.setStatus("Importing ROM…");
        new Thread(() -> {
            try {
                ImportedRom imported = importRom(uri);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) {
                        return;
                    }
                    importing = false;
                    romFile = imported.file;
                    romId = imported.id;
                    if (resumed) {
                        startRunner();
                    }
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration) {
                        importing = false;
                        romUri = null;
                        romFile = null;
                        romId = null;
                        emulatorView.setStatus("Could not import ROM — tap to retry");
                        Toast.makeText(this, "Could not import the selected ROM", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "rom-import").start();
    }

    private ImportedRom importRom(Uri uri) throws IOException {
        File directory = new File(getFilesDir(), "roms");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private ROM directory");
        }
        File temporary = File.createTempFile("import-", ".tmp", directory);
        byte[] hash;
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IOException("Content provider returned no data");
            }
            hash = RomArchive.extractRom(input, output, MAX_ROM_BYTES);
            output.getFD().sync();
        } catch (IOException | RuntimeException e) {
            temporary.delete();
            throw e;
        }

        String id = hex(hash);
        File destination = new File(directory, id + ".gba");
        if (destination.isFile()) {
            temporary.delete();
        } else if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not finish private ROM import");
        }
        return new ImportedRom(destination, id);
    }

    private void startRunner() {
        if (!resumed || romFile == null || romId == null || runner != null) {
            return;
        }
        runner = new EmulationRunner(this, emulatorView, romFile, romId, message ->
                runOnUiThread(() -> {
                    runner = null;
                    romFile = null;
                    romId = null;
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

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            result[i * 2] = digits[(bytes[i] >>> 4) & 0xF];
            result[i * 2 + 1] = digits[bytes[i] & 0xF];
        }
        return new String(result);
    }

    private static final class ImportedRom {
        final File file;
        final String id;

        ImportedRom(File file, String id) {
            this.file = file;
            this.id = id;
        }
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
