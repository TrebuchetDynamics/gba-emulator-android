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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

final class EmulationRunner implements Runnable {
    interface ErrorListener {
        void onError(String message);
    }

    interface StateListener {
        void onStateSaved(int slot);
        void onStateLoaded(int slot);
        void onAutoStateLoaded();
        void onRewound(int seconds);
        void onStateError(String message);
    }

    private enum CommandType { SAVE, LOAD, LOAD_AUTO, REWIND, RESET }

    private static final class Command {
        final CommandType type;
        final int slot;
        Command(CommandType type, int slot) {
            this.type = type;
            this.slot = slot;
        }
    }

    private static final long FRAME_NANOS = 16_743_000L;
    private static final String PERF_TAG = "MgbaPerf";
    private static final long PERF_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final int REWIND_INTERVAL_FRAMES = 60;
    private static final int MAX_REWIND_SECONDS = 5;
    private static final int REWIND_MEMORY_BYTES = 24 * 1024 * 1024;

    private final Context context;
    private final EmulatorView view;
    private final File rom;
    private final String romId;
    private final ErrorListener errors;
    private final Thread thread;
    private volatile boolean running = true;
    private final SaveStateStore states;
    private final StateListener stateListener;
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private volatile boolean fastForward;
    private volatile boolean slowMotion;
    private volatile boolean muted;
    private final boolean audioEnabled;
    private final float audioVolume; // 0f..1f
    private final int fastForwardSpeed;
    private final int frameskip;
    private final int[] dmgPaletteArgb;
    private final boolean autoResume;
    private final RewindBuffer rewind = new RewindBuffer(REWIND_MEMORY_BYTES);
    private volatile int rewindSnapshots;

    EmulationRunner(Context context, EmulatorView view, File rom, String romId,
                    SaveStateStore states, ErrorListener errors,
                    StateListener stateListener,
                    boolean audioEnabled, float audioVolume, int fastForwardSpeed,
                    int frameskip, int[] dmgPaletteArgb, boolean autoResume) {
        this.context = context.getApplicationContext();
        this.view = view;
        this.rom = rom;
        this.romId = romId;
        this.states = states;
        this.errors = errors;
        this.stateListener = stateListener;
        this.audioEnabled = audioEnabled;
        this.audioVolume = audioVolume;
        this.fastForwardSpeed = fastForwardSpeed;
        this.frameskip = Math.max(0, frameskip);
        this.dmgPaletteArgb = dmgPaletteArgb;
        this.autoResume = autoResume;
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

    void postSaveState(int slot) {
        commands.add(new Command(CommandType.SAVE, slot));
    }

    void postLoadState(int slot) {
        commands.add(new Command(CommandType.LOAD, slot));
    }

    void postLoadAutoState() {
        commands.add(new Command(CommandType.LOAD_AUTO, 0));
    }

    void postLoadAutoState(int generation) {
        if (generation < 1 || generation > SaveStateStore.AUTO_SLOT_COUNT) {
            throw new IllegalArgumentException("Autosave generation out of range");
        }
        commands.add(new Command(CommandType.LOAD_AUTO, generation));
    }

    void postRewind(int seconds) {
        if (seconds < 1 || seconds > MAX_REWIND_SECONDS) {
            throw new IllegalArgumentException("Rewind must be 1..5 seconds");
        }
        commands.add(new Command(CommandType.REWIND, seconds));
    }

    int rewindSecondsAvailable() {
        return availableRewindSeconds(rewindSnapshots);
    }

    static int availableRewindSeconds(int snapshots) {
        return Math.max(0, Math.min(MAX_REWIND_SECONDS, snapshots - 1));
    }

    void postReset() {
        commands.add(new Command(CommandType.RESET, 0));
    }

    void setFastForward(boolean on) {
        if (on) {
            slowMotion = false;
        }
        fastForward = on;
    }

    boolean isFastForward() {
        return fastForward;
    }

    void setSlowMotion(boolean on) {
        if (on) {
            fastForward = false;
        }
        slowMotion = on;
    }

    boolean isSlowMotion() {
        return slowMotion;
    }

    void setMuted(boolean muted) {
        this.muted = muted;
    }

    static float effectiveAudioVolume(float configuredVolume, boolean muted) {
        return muted ? 0f : configuredVolume;
    }

    static long frameBudgetNanos(boolean fastForward, int fastForwardSpeed) {
        return frameBudgetNanos(fastForward, fastForwardSpeed, false);
    }

    static long frameBudgetNanos(boolean fastForward, int fastForwardSpeed,
                                 boolean slowMotion) {
        if (fastForward) {
            return FRAME_NANOS / fastForwardSpeed;
        }
        return slowMotion ? FRAME_NANOS * 2 : FRAME_NANOS;
    }

    /** True when frame {@code frameIndex} should be blitted under {@code frameskip}. */
    static boolean shouldRenderFrame(long frameIndex, int frameskip) {
        return frameIndex % (frameskip + 1) == 0;
    }

    private static RomSystem detectSystem(File rom) {
        try (java.io.InputStream in = new java.io.FileInputStream(rom)) {
            byte[] head = new byte[0x150];
            int off = 0;
            int n;
            while (off < head.length && (n = in.read(head, off, head.length - off)) > 0) {
                off += n;
            }
            byte[] slice = off == head.length ? head : java.util.Arrays.copyOf(head, off);
            return RomSystem.detect(slice);
        } catch (java.io.IOException e) {
            return RomSystem.UNKNOWN;
        }
    }

    @Override
    public void run() {
        AudioTrack audioTrack = null;
        view.setStatus("Starting mGBA…");
        RomSystem system = detectSystem(rom);
        int platform = system.isGameBoy() ? MgbaSession.PLATFORM_GB : MgbaSession.PLATFORM_GBA;
        try (MgbaSession session = new MgbaSession(platform)) {
            session.loadRom(rom);
            view.setVideoSize(session.videoWidth(), session.videoHeight(), !system.isGameBoy());
            // Recolour only original Game Boy (DMG) games; GBC and GBA are untouched.
            if (system.usesDmgPalette() && dmgPaletteArgb != null) {
                session.setDmgPalette(dmgPaletteArgb);
            }
            File saveFile = saveFile();
            restoreSavedata(session, saveFile);
            if (audioEnabled) {
                audioTrack = createAudioTrack();
            }
            boolean appliedMuted = muted;
            if (audioTrack != null) {
                audioTrack.setVolume(effectiveAudioVolume(audioVolume, appliedMuted));
                audioTrack.play();
            }

            int[] pixels = new int[session.framePixels()];
            short[] audio = new short[MgbaSession.MIN_AUDIO_BUFFER_SAMPLES];
            FrameStats stats = new FrameStats(FRAME_NANOS);
            long nextPerfLog = SystemClock.elapsedRealtimeNanos()
                    + PERF_LOG_INTERVAL_NANOS;
            long nextFrame = SystemClock.elapsedRealtimeNanos();
            long frameIndex = 0;
            while (running) {
                applyCommands(session);
                boolean ff = fastForward;
                boolean slow = slowMotion;
                long budget = frameBudgetNanos(ff, fastForwardSpeed, slow);
                long frameStart = SystemClock.elapsedRealtimeNanos();
                int audioFrames = session.runFrame(
                        view.keysForFrame(frameIndex), pixels, audio);
                long nativeEnd = SystemClock.elapsedRealtimeNanos();
                long nativeNanos = nativeEnd - frameStart;
                if (audioFrames < 0) {
                    throw new IllegalStateException("mGBA failed to run a frame");
                }

                boolean muteNow = muted;
                if (muteNow != appliedMuted) {
                    if (audioTrack != null) {
                        audioTrack.setVolume(effectiveAudioVolume(audioVolume, muteNow));
                    }
                    appliedMuted = muteNow;
                }

                // Altered-speed audio would stutter because the core still emits one normal
                // frame of samples, so keep both fast-forward and slow motion silent.
                long audioNanos = 0;
                if (!ff && !slow && audioTrack != null && audioFrames > 0) {
                    long audioStart = SystemClock.elapsedRealtimeNanos();
                    audioTrack.write(audio, 0, audioFrames * 2, AudioTrack.WRITE_BLOCKING);
                    audioNanos = SystemClock.elapsedRealtimeNanos() - audioStart;
                }

                long publishNanos = 0;
                if (shouldRenderFrame(frameIndex, frameskip)) {
                    long publishStart = SystemClock.elapsedRealtimeNanos();
                    view.publishFrame(pixels);
                    publishNanos = SystemClock.elapsedRealtimeNanos() - publishStart;
                }
                frameIndex++;

                long rewindNanos = 0;
                if (frameIndex % REWIND_INTERVAL_FRAMES == 0) {
                    long rewindStart = SystemClock.elapsedRealtimeNanos();
                    try {
                        rewind.push(session.saveState());
                        rewindSnapshots = rewind.size();
                    } catch (RuntimeException ignored) {
                        rewind.clear();
                        rewindSnapshots = 0;
                    }
                    rewindNanos = SystemClock.elapsedRealtimeNanos() - rewindStart;
                }

                long now = SystemClock.elapsedRealtimeNanos();
                stats.record(now - frameStart, nativeNanos, audioNanos,
                        publishNanos, rewindNanos);
                if (now >= nextPerfLog && stats.hasFrames()) {
                    int cumulativeUnderruns = audioTrack == null
                            ? 0 : audioTrack.getUnderrunCount();
                    Log.i(PERF_TAG, stats.summarizeAndReset(cumulativeUnderruns));
                    nextPerfLog = now + PERF_LOG_INTERVAL_NANOS;
                }

                nextFrame += budget;
                long wait = nextFrame - SystemClock.elapsedRealtimeNanos();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else if (wait < -budget * 4) {
                    nextFrame = SystemClock.elapsedRealtimeNanos();
                }
            }
            if (autoResume) {
                try {
                    states.writeAuto(session.saveState());
                } catch (IOException | RuntimeException ignored) {
                    // Battery save persistence below still gets its chance.
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

    private void applyCommands(MgbaSession session) {
        Command command;
        while ((command = commands.poll()) != null) {
            try {
                switch (command.type) {
                    case SAVE:
                        states.write(command.slot, session.saveState());
                        stateListener.onStateSaved(command.slot);
                        break;
                    case LOAD:
                        byte[] state = states.read(command.slot);
                        if (state == null) {
                            stateListener.onStateError("Slot " + command.slot + " is empty");
                        } else {
                            session.loadState(state);
                            rewind.clear();
                            rewindSnapshots = 0;
                            stateListener.onStateLoaded(command.slot);
                        }
                        break;
                    case LOAD_AUTO:
                        byte[] autosave = command.slot == 0
                                ? states.readLatestAuto() : states.readAuto(command.slot);
                        if (autosave == null) {
                            stateListener.onStateError("Recovery save is unavailable");
                        } else {
                            session.loadState(autosave);
                            rewind.clear();
                            rewindSnapshots = 0;
                            stateListener.onAutoStateLoaded();
                        }
                        break;
                    case REWIND:
                        byte[] previous = rewind.rewind(command.slot);
                        if (previous == null) {
                            stateListener.onStateError("Keep playing to build rewind history");
                        } else {
                            session.loadState(previous);
                            rewindSnapshots = rewind.size();
                            stateListener.onRewound(command.slot);
                        }
                        break;
                    case RESET:
                        session.reset();
                        rewind.clear();
                        rewindSnapshots = 0;
                        break;
                }
            } catch (IOException | RuntimeException e) {
                // A bad state must never kill the emulation loop.
                stateListener.onStateError(e.getMessage() == null
                        ? "Save-state operation failed" : e.getMessage());
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
        track.setVolume(audioVolume);
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

    /** Memory-capped timeline; rewinding discards the future branch. */
    static final class RewindBuffer {
        private final ArrayDeque<byte[]> states = new ArrayDeque<>();
        private final int maxBytes;
        private int bytes;

        RewindBuffer(int maxBytes) {
            this.maxBytes = Math.max(1, maxBytes);
        }

        void push(byte[] state) {
            if (state == null || state.length == 0 || state.length > maxBytes) {
                clear();
                return;
            }
            while (!states.isEmpty() && bytes + state.length > maxBytes) {
                bytes -= states.removeFirst().length;
            }
            states.addLast(state);
            bytes += state.length;
        }

        byte[] rewind(int steps) {
            if (states.size() < 2) {
                return null;
            }
            int count = Math.min(Math.max(1, steps), states.size() - 1);
            while (count-- > 0) {
                bytes -= states.removeLast().length;
            }
            return states.peekLast();
        }

        int size() {
            return states.size();
        }

        void clear() {
            states.clear();
            bytes = 0;
        }
    }
}
