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

    // --- registry geometry (forward-compat for a climb detector) ---

    private static RopeStore.Segment seg(int[] a, int[] b) {
        return new RopeStore.Segment("minecraft:overworld", a, b, "00000000-0000-0000-0000-000000000000", null);
    }

    @Test
    void verticalSegmentIsNearVertical() {
        var g = RopeRegistry.geometryOf(seg(new int[] {0, 64, 0}, new int[] {0, 70, 0}));
        assertEquals(Double.POSITIVE_INFINITY, g.slope());
        assertTrue(g.isNearVertical(Math.toRadians(60)));
        assertEquals(Math.PI / 2, g.pitch(), 1e-9);
    }

    @Test
    void flatSegmentIsNotNearVertical() {
        var g = RopeRegistry.geometryOf(seg(new int[] {0, 64, 0}, new int[] {6, 64, 0}));
        assertEquals(0.0, g.slope(), 1e-9);
        assertFalse(g.isNearVertical(Math.toRadians(60)));
    }

    @Test
    void distanceToSegmentClampsToExtent() {
        // horizontal rope from (0,64,0) to (6,64,0); a point beside its midpoint is ~1 block off.
        var s = seg(new int[] {0, 64, 0}, new int[] {6, 64, 0});
        double mid = RopeRegistry.distanceToSegment(s, 3.5, 65.5, 0.5); // centre-ish, 1 above line
        assertTrue(mid < 1.2 && mid > 0.9, "off-by-~1 beside the midpoint, got " + mid);
        // A point far past the end clamps to the endpoint, not the infinite line.
        double past = RopeRegistry.distanceToSegment(s, 100.0, 64.5, 0.5);
        assertTrue(past > 90.0);
    }

    @Test
    void diagonalPitchIsBetween() {
        // rise 6 over run 6 → 45° → climbable at a 40° threshold, not at 60°.
        var g = RopeRegistry.geometryOf(seg(new int[] {0, 64, 0}, new int[] {6, 70, 0}));
        assertEquals(Math.toRadians(45), g.pitch(), 1e-9);
        assertTrue(g.isNearVertical(Math.toRadians(40)));
        assertFalse(g.isNearVertical(Math.toRadians(60)));
    }
}
// NOTE: nearestClimbable() over RopeStore is covered at runtime, not here — RopeStore.segments()
// lazily loads via FabricLoader.getConfigDir(), which isn't available in a pure-JUnit context.
