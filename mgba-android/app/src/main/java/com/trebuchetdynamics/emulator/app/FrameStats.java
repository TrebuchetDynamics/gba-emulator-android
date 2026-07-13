package com.trebuchetdynamics.emulator.app;

import java.util.Locale;

/** Accumulates per-frame work durations for one logging window. Not thread-safe. */
final class FrameStats {
    private final long budgetNanos;
    private long frames;
    private long lateFrames;
    private long totalNanos;
    private long maxNanos;

    FrameStats(long budgetNanos) {
        this.budgetNanos = budgetNanos;
    }

    void record(long frameNanos) {
        frames++;
        totalNanos += frameNanos;
        if (frameNanos > maxNanos) {
            maxNanos = frameNanos;
        }
        if (frameNanos > budgetNanos) {
            lateFrames++;
        }
    }

    boolean hasFrames() {
        return frames > 0;
    }

    /** Formats one summary line for the window and resets it. Requires hasFrames(). */
    String summarizeAndReset(int underruns) {
        String line = String.format(Locale.US,
                "frames=%d avg_us=%d max_us=%d late=%d underruns=%d",
                frames, totalNanos / frames / 1_000, maxNanos / 1_000,
                lateFrames, underruns);
        frames = 0;
        lateFrames = 0;
        totalNanos = 0;
        maxNanos = 0;
        return line;
    }
}
