package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmulationRunnerTest {
    private static final long FRAME_NANOS = 16_743_000L;

    @Test
    public void muteOnlyChangesEffectiveOutputVolume() {
        assertEquals(0.65f, EmulationRunner.effectiveAudioVolume(0.65f, false), 0f);
        assertEquals(0f, EmulationRunner.effectiveAudioVolume(0.65f, true), 0f);
    }

    @Test
    public void normalSpeedUsesTheFullFrameBudget() {
        assertEquals(FRAME_NANOS, EmulationRunner.frameBudgetNanos(false, 4));
    }

    @Test
    public void fastForwardDividesTheBudgetBySpeed() {
        assertEquals(FRAME_NANOS / 4, EmulationRunner.frameBudgetNanos(true, 4));
        assertEquals(FRAME_NANOS / 2, EmulationRunner.frameBudgetNanos(true, 2));
        assertEquals(FRAME_NANOS / 3, EmulationRunner.frameBudgetNanos(true, 3));
    }

    @Test
    public void slowMotionDoublesTheFrameBudget() {
        assertEquals(FRAME_NANOS * 2,
                EmulationRunner.frameBudgetNanos(false, 4, true));
        assertEquals(FRAME_NANOS / 4,
                EmulationRunner.frameBudgetNanos(true, 4, true));
    }

    @Test
    public void frameskipZeroRendersEveryFrame() {
        for (long i = 0; i < 6; i++) {
            assertTrue(EmulationRunner.shouldRenderFrame(i, 0));
        }
    }

    @Test
    public void frameskipOneRendersEveryOtherFrame() {
        assertTrue(EmulationRunner.shouldRenderFrame(0, 1));
        assertFalse(EmulationRunner.shouldRenderFrame(1, 1));
        assertTrue(EmulationRunner.shouldRenderFrame(2, 1));
        assertFalse(EmulationRunner.shouldRenderFrame(3, 1));
    }

    @Test
    public void frameskipThreeRendersOneInFour() {
        assertTrue(EmulationRunner.shouldRenderFrame(0, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(1, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(2, 3));
        assertFalse(EmulationRunner.shouldRenderFrame(3, 3));
        assertTrue(EmulationRunner.shouldRenderFrame(4, 3));
    }

    @Test
    public void availableRewindSecondsTracksHistoryAndCapsAtFive() {
        assertEquals(0, EmulationRunner.availableRewindSeconds(0));
        assertEquals(1, EmulationRunner.availableRewindSeconds(2));
        assertEquals(3, EmulationRunner.availableRewindSeconds(4));
        assertEquals(5, EmulationRunner.availableRewindSeconds(99));
    }

    @Test
    public void rewindBufferIsBoundedAndDiscardsTheFuture() {
        EmulationRunner.RewindBuffer buffer = new EmulationRunner.RewindBuffer(3);
        buffer.push(new byte[] {1});
        buffer.push(new byte[] {2});
        buffer.push(new byte[] {3});
        buffer.push(new byte[] {4});
        assertEquals(3, buffer.size());
        assertArrayEquals(new byte[] {2}, buffer.rewind(2));
        assertEquals(1, buffer.size());
        assertNull(buffer.rewind(1));
    }

    @Test
    public void oversizedRewindStateClearsUnsafeHistory() {
        EmulationRunner.RewindBuffer buffer = new EmulationRunner.RewindBuffer(2);
        buffer.push(new byte[] {1});
        buffer.push(new byte[] {1, 2, 3});
        assertEquals(0, buffer.size());
    }
}
