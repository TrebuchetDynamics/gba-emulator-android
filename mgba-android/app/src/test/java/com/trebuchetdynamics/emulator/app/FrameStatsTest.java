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
}
