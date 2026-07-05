package com.k33bz.ropes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the 11-block-segment rule the spike measured (11 stable, snap at 12).
 * No game runtime needed.
 */
class RopeMathTest {

    @Test
    void axisAlignedSpan() {
        assertEquals(11.0, RopeMath.span(0, 0, 0, 11, 0, 0), 1e-9);
        assertEquals(3.0, RopeMath.span(0, 0, 0, 0, 3, 0), 1e-9);
    }

    @Test
    void diagonalSpan() {
        // 2,0,2 → sqrt(8) ≈ 2.828 (the spike's diagonal case)
        assertEquals(Math.sqrt(8), RopeMath.span(0, 0, 0, 2, 0, 2), 1e-9);
    }

    @Test
    void elevenBlocksIsWithin_twelveIsNot() {
        assertTrue(RopeMath.withinSpan(11.0, 11), "11 blocks must be allowed (spike: stable)");
        assertFalse(RopeMath.withinSpan(12.0, 11), "12 blocks must be rejected (spike: snaps)");
    }

    @Test
    void diagonalRespectsStraightLineNotBlockCount() {
        // 8,0,8 is 8 blocks on each axis but sqrt(128)≈11.31 straight-line → beyond 11.
        double span = RopeMath.span(0, 0, 0, 8, 0, 8);
        assertTrue(span > 11.0);
        assertFalse(RopeMath.withinSpan(span, 11));
    }

    @Test
    void clampKeepsSafeRange() {
        assertEquals(11, RopeMath.clampMaxSpan(11));
        assertEquals(11, RopeMath.clampMaxSpan(50), "over-11 clamps to 11 (vanilla snaps at 12)");
        assertEquals(1, RopeMath.clampMaxSpan(0));
        assertEquals(5, RopeMath.clampMaxSpan(5));
    }
}
