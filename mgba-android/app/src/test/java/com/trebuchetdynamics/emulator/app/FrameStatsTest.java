package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameStatsTest {
    private static final long BUDGET_NANOS = 16_743_000L;

    @Test
    public void summarizesWindowAndCountsLateFrames() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        assertFalse(stats.hasFrames());
        stats.record(10_000_000L);
        stats.record(20_000_000L);
        assertTrue(stats.hasFrames());
        assertEquals("frames=2 avg_us=15000 max_us=20000 late=1 underruns=3",
                stats.summarizeAndReset(3));
    }

    @Test
    public void resetsAfterSummary() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        stats.record(20_000_000L);
        stats.summarizeAndReset(0);
        assertFalse(stats.hasFrames());
        stats.record(1_000_000L);
        assertEquals("frames=1 avg_us=1000 max_us=1000 late=0 underruns=7",
                stats.summarizeAndReset(7));
    }

    @Test
    public void reportsUnderrunsAsPerWindowDeltaOfCumulativeCount() {
        // AudioTrack.getUnderrunCount() is cumulative since track creation; each
        // window must log only the increase since the previous window, not the
        // running total, or a single glitch would falsely flag every later window.
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        stats.record(10_000_000L);
        assertEquals("frames=1 avg_us=10000 max_us=10000 late=0 underruns=5",
                stats.summarizeAndReset(5));

        stats.record(10_000_000L);
        assertEquals("frames=1 avg_us=10000 max_us=10000 late=0 underruns=2",
                stats.summarizeAndReset(7));

        stats.record(10_000_000L);
        assertEquals("frames=1 avg_us=10000 max_us=10000 late=0 underruns=0",
                stats.summarizeAndReset(7));
    }

    @Test(expected = IllegalStateException.class)
    public void summarizeAndResetRequiresFrames() {
        FrameStats stats = new FrameStats(BUDGET_NANOS);
        stats.summarizeAndReset(0);
    }
}
