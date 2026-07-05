package com.k33bz.ropes;

/**
 * Pure span math — extracted so the 11-block-segment rule can be unit-tested without a game
 * runtime (the same "pure-logic tests" convention as postbox's Postage / sanctuary's
 * SurvivalLogic). The spike measured: 11 blocks stable, snap at 12.
 */
public final class RopeMath {
    private RopeMath() {
    }

    /** Straight-line distance between two integer block positions. */
    public static double span(int ax, int ay, int az, int bx, int by, int bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether a segment of this span is within the per-segment ceiling (vanilla snaps past it). */
    public static boolean withinSpan(double span, int maxSpanBlocks) {
        return span <= maxSpanBlocks;
    }

    /** Clamp a configured max span into the safe 1..11 range (vanilla snaps a leash at 12). */
    public static int clampMaxSpan(int configured) {
        return Math.max(1, Math.min(11, configured));
    }
}
