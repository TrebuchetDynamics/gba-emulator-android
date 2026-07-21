package com.trebuchetdynamics.emulator.app;

import java.util.Locale;

/** Accumulates per-frame work durations for one logging window. Not thread-safe. */
final class FrameStats {
    private final long budgetNanos;
    private long frames;
    private long lateFrames;
    private long totalNanos;
    private long maxNanos;
    private long nativeNanos;
    private long audioNanos;
    private long publishNanos;
    private long rewindNanos;
    private long rewindMaxNanos;
    private int lastCumulativeUnderruns;

    FrameStats(long budgetNanos) {
        this.budgetNanos = budgetNanos;
    }

    boolean record(long frameNanos, long nativeFrameNanos, long audioFrameNanos,
            long publishFrameNanos, long rewindFrameNanos) {
        if (frameNanos < 0 || nativeFrameNanos < 0 || audioFrameNanos < 0
                || publishFrameNanos < 0 || rewindFrameNanos < 0) {
            return false;
        }
        long attributed = nativeFrameNanos;
        if (audioFrameNanos > frameNanos - attributed) return false;
        attributed += audioFrameNanos;
        if (publishFrameNanos > frameNanos - attributed) return false;
        attributed += publishFrameNanos;
        if (rewindFrameNanos > frameNanos - attributed) return false;

        frames++;
        totalNanos += frameNanos;
        nativeNanos += nativeFrameNanos;
        audioNanos += audioFrameNanos;
        publishNanos += publishFrameNanos;
        rewindNanos += rewindFrameNanos;
        maxNanos = Math.max(maxNanos, frameNanos);
        rewindMaxNanos = Math.max(rewindMaxNanos, rewindFrameNanos);
        if (frameNanos > budgetNanos) lateFrames++;
        return true;
    }

    boolean hasFrames() {
        return frames > 0;
    }

    String summarizeAndReset(int cumulativeUnderruns) {
        if (frames == 0) {
            throw new IllegalStateException("summarizeAndReset() requires hasFrames()");
        }
        int windowUnderruns = cumulativeUnderruns - lastCumulativeUnderruns;
        lastCumulativeUnderruns = cumulativeUnderruns;
        long otherNanos = totalNanos - nativeNanos - audioNanos - publishNanos - rewindNanos;
        String line = String.format(Locale.US,
                "frames=%d avg_us=%d max_us=%d late=%d underruns=%d "
                        + "native_us=%d audio_us=%d publish_us=%d rewind_us=%d "
                        + "other_us=%d rewind_max_us=%d",
                frames, totalNanos / frames / 1_000, maxNanos / 1_000,
                lateFrames, windowUnderruns, nativeNanos / frames / 1_000,
                audioNanos / frames / 1_000, publishNanos / frames / 1_000,
                rewindNanos / frames / 1_000, otherNanos / frames / 1_000,
                rewindMaxNanos / 1_000);
        frames = 0;
        lateFrames = 0;
        totalNanos = 0;
        maxNanos = 0;
        nativeNanos = 0;
        audioNanos = 0;
        publishNanos = 0;
        rewindNanos = 0;
        rewindMaxNanos = 0;
        return line;
    }
}
