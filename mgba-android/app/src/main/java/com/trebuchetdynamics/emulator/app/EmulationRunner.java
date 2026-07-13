package com.trebuchetdynamics.emulator.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import com.trebuchetdynamics.emulator.mgba.MgbaSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

final class EmulationRunner implements Runnable {
    interface ErrorListener {
        void onError(String message);
    }

    private static final long FRAME_NANOS = 16_743_000L;
    private static final String PERF_TAG = "MgbaPerf";
    private static final long PERF_LOG_INTERVAL_NANOS = 10_000_000_000L;

    private final Context context;
    private final EmulatorView view;
    private final File rom;
    private final String romId;
    private final ErrorListener errors;
    private final Thread thread;
    private volatile boolean running = true;

    EmulationRunner(Context context, EmulatorView view, File rom, String romId,
                    ErrorListener errors) {
        this.context = context.getApplicationContext();
        this.view = view;
        this.rom = rom;
        this.romId = romId;
        this.errors = errors;
        thread = new Thread(this, "mgba-emulation");
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        AudioTrack audioTrack = null;
        view.setStatus("Starting mGBA…");
        try (MgbaSession session = new MgbaSession()) {
            session.loadRom(rom);
            File saveFile = saveFile();
            restoreSavedata(session, saveFile);
            audioTrack = createAudioTrack();
            if (audioTrack != null) {
                audioTrack.play();
            }

            int[] pixels = new int[MgbaSession.FRAME_PIXELS];
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
            FrameStats stats = new FrameStats(FRAME_NANOS);
            long nextPerfLog = SystemClock.elapsedRealtimeNanos()
                    + PERF_LOG_INTERVAL_NANOS;
            long nextFrame = SystemClock.elapsedRealtimeNanos();
            while (running) {
                long frameStart = SystemClock.elapsedRealtimeNanos();
                int audioFrames = session.runFrame(view.keys(), pixels, audio);
                if (audioFrames < 0) {
                    throw new IllegalStateException("mGBA failed to run a frame");
                }
                if (audioTrack != null && audioFrames > 0) {
                    audioTrack.write(audio, 0, audioFrames * 2, AudioTrack.WRITE_BLOCKING);
                }
                view.publishFrame(pixels);
                long now = SystemClock.elapsedRealtimeNanos();
                stats.record(now - frameStart);
                if (now >= nextPerfLog && stats.hasFrames()) {
                    int underruns = audioTrack == null
                            ? 0 : audioTrack.getUnderrunCount();
                    Log.i(PERF_TAG, stats.summarizeAndReset(underruns));
                    nextPerfLog = now + PERF_LOG_INTERVAL_NANOS;
                }

                nextFrame += FRAME_NANOS;
                long wait = nextFrame - SystemClock.elapsedRealtimeNanos();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else if (wait < -FRAME_NANOS * 4) {
                    nextFrame = SystemClock.elapsedRealtimeNanos();
                }
            }
            persistSavedata(session, saveFile);
        } catch (RuntimeException e) {
            errors.onError(e.getMessage() == null ? "mGBA stopped unexpectedly" : e.getMessage());
        } finally {
            if (audioTrack != null) {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.release();
            }
        }
    }

    private AudioTrack createAudioTrack() {
        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minimum = AudioTrack.getMinBufferSize(MgbaSession.AUDIO_SAMPLE_RATE, channelMask, encoding);
        if (minimum <= 0) {
            return null;
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(MgbaSession.AUDIO_SAMPLE_RATE)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(Math.max(minimum, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            return null;
        }
        return track;
    }

    private File saveFile() {
        File directory = new File(context.getFilesDir(), "saves");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return new File(directory, romId + ".sav");
    }

    private static void restoreSavedata(MgbaSession session, File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > 1024 * 1024) {
            return;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset == data.length) {
                session.restoreSavedata(data);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // A stale or incompatible save must not prevent the ROM from starting.
        }
    }

    private static void persistSavedata(MgbaSession session, File file) {
        byte[] data = session.copySavedata();
        if (data.length == 0) {
            return;
        }
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data);
            output.getFD().sync();
            if (!temporary.renameTo(file)) {
                throw new IOException("Could not replace cartridge save");
            }
        } catch (IOException ignored) {
            temporary.delete();
        }
    }

}
