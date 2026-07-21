package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameStatsTest {
    private static final long BUDGET_NANOS = 16_743_000L;

    private static void record(FrameStats stats, long totalNanos) {
        assertTrue(stats.record(totalNanos, 0, 0, 0, 0));
    }

    @Test
    public void summarizesWindowAndCountsLateFrames() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertFalse(stats.hasFrames());
        record(stats, 10_000_000L);
        record(stats, 20_000_000L);
        assertTrue(stats.hasFrames());
        assertEquals("frames=2 avg_us=15000 max_us=20000 late=1 underruns=3 "
                        + "native_us=0 audio_us=0 publish_us=0 rewind_us=0 "
                        + "other_us=15000 rewind_max_us=0",
                stats.summarizeAndReset(3));
    }

    @Test
    public void reportsAveragePhaseContributionsAndRewindMaximum() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertTrue(stats.record(10_000_000L, 4_000_000L, 2_000_000L,
                1_000_000L, 0));
        assertTrue(stats.record(20_000_000L, 6_000_000L, 4_000_000L,
                2_000_000L, 5_000_000L));
        assertEquals("frames=2 avg_us=15000 max_us=20000 late=1 underruns=0 "
                        + "native_us=5000 audio_us=3000 publish_us=1500 rewind_us=2500 "
                        + "other_us=3000 rewind_max_us=5000",
                stats.summarizeAndReset(0));
    }

    @Test
    public void rejectsInvalidSamplesWithoutAffectingTheWindow() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertFalse(stats.record(-1, 0, 0, 0, 0));
        assertFalse(stats.record(10, 11, 0, 0, 0));
        assertFalse(stats.record(10, 4, 4, 4, 0));
        assertFalse(stats.hasFrames());
    }

    @Test
    public void resetsAllPhasesAfterSummary() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertTrue(stats.record(20_000_000L, 10_000_000L, 0, 0, 5_000_000L));
        stats.summarizeAndReset(0);
        assertFalse(stats.hasFrames());
        record(stats, 1_000_000L);
        assertEquals("frames=1 avg_us=1000 max_us=1000 late=0 underruns=7 "
                        + "native_us=0 audio_us=0 publish_us=0 rewind_us=0 "
                        + "other_us=1000 rewind_max_us=0",
                stats.summarizeAndReset(7));
    }

    @Test
    public void reportsUnderrunsAsPerWindowDeltaOfCumulativeCount() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        record(stats, 10_000_000L);
        assertTrue(stats.summarizeAndReset(5).contains("underruns=5"));
        record(stats, 10_000_000L);
        assertTrue(stats.summarizeAndReset(7).contains("underruns=2"));
        record(stats, 10_000_000L);
        assertTrue(stats.summarizeAndReset(7).contains("underruns=0"));
    }

    @Test(expected = IllegalStateException.class)
    public void summarizeAndResetRequiresFrames() {
        new FrameStats(BUDGET_NANOS).summarizeAndReset(0);
    }
}
