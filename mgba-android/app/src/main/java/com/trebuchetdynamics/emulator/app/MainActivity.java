package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
    private FrameLayout root;
    private InGameMenuView menu;
    private SaveStateStore states;
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
        states = new SaveStateStore(new File(getFilesDir(), "states"), romId);
        runner = new EmulationRunner(this, emulatorView, romFile, romId, states,
                message -> runOnUiThread(() -> {
                    runner = null;
                    romFile = null;
                    romId = null;
                    romUri = null;
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
