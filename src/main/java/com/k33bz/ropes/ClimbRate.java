package com.k33bz.ropes;

/**
 * The pure angle&rarr;rate curve for rope climbing, plus the Levitation duty-cycle helper — all
 * pure functions so the whole rate model is unit-tested in CI with no server (k33bz decision #1:
 * angle scales the rate off a fixed floor at the gate angle).
 *
 * <p>Model: at the gate angle ({@code minAngleDeg}) the ascend rate is {@code floorRate}; it
 * scales linearly up to {@code verticalRate} at 90°; and it is hard-capped at {@code maxRate}
 * (which must stay below the vanilla ladder ascend, ~2.35 b/s — asserted in tests). Angles below
 * the gate are not climbable and return rate 0.</p>
 *
 * <p><b>Why a duty cycle:</b> Levitation amplitude is an integer effect — measured on 26.1.2 at
 * roughly {@code amp0 ≈ 0.9 b/s} and {@code amp1 ≈ 1.8 b/s} actual. To realise a smooth sub-amp0
 * target like 0.4 b/s we apply amp0 for {@code k} of every {@code N} ticks (duty cycle
 * {@code k/N ≈ target/amp0}). {@link #dutyOnThisTick} decides, deterministically per tick, whether
 * this tick is an "on" tick.</p>
 */
public final class ClimbRate {
    private ClimbRate() {
    }

    /** Vanilla ladder ascend speed (blocks/second) — the hard ceiling climbing must stay under. */
    public static final double LADDER_ASCEND = 2.35;

    /**
     * Measured actual ascend rate (b/s) of Levitation amplitude 0, applied CONTINUOUSLY (every
     * tick — exactly how the mod applies it) on 26.1.2: <b>0.891 b/s</b> (theoretical 0.9). This
     * is the climb FLOOR. See the v0.2.0 rate-mechanism note in the README for why the floor is
     * amp0 (0.9) and not the 0.4 config value: a sub-amp0 duty cycle must REMOVE levitation on
     * off-ticks, and gravity on those ticks makes the low net rate jittery/bouncy — so per the
     * design fallback we floor at continuous amp0 ("usable, not a crawl").
     */
    public static final double LEVITATION_AMP0_RATE = 0.891;

    /** Measured actual ascend rate (b/s) of Levitation amplitude 1, continuous, on 26.1.2 (~1.8). */
    public static final double LEVITATION_AMP1_RATE = 1.806;

    /**
     * Target ascend rate (blocks/second) for a segment at {@code angleDeg}, given the config curve.
     * Returns 0 for angles below the gate (not climbable). Monotonically non-decreasing in angle,
     * clamped to {@code maxRate}.
     */
    public static double targetRate(double angleDeg, double minAngleDeg, double floorRate,
                                    double verticalRate, double maxRate) {
        if (angleDeg < minAngleDeg) {
            return 0.0;
        }
        double span = 90.0 - minAngleDeg;
        double t = span <= 1.0e-9 ? 1.0 : Math.max(0.0, Math.min(1.0, (angleDeg - minAngleDeg) / span));
        double rate = floorRate + t * (verticalRate - floorRate);
        return Math.min(rate, maxRate);
    }

    /**
     * The duty-cycle fraction (0..1) needed to realise {@code targetRate} by pulsing Levitation
     * amplitude 0 (which lifts at {@code amp0Rate}). Clamped to [0,1]; a target at/above amp0 runs
     * at full duty (every tick).
     */
    public static double duty(double targetRate, double amp0Rate) {
        if (amp0Rate <= 1.0e-9) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, targetRate / amp0Rate));
    }

    /**
     * Deterministic duty-cycle gate: given a monotonically increasing tick counter and a duty
     * fraction, decide whether the "high" action applies THIS tick. Uses an evenly-spaced
     * (Bresenham) schedule so on-ticks are spread across the cycle, not bunched — smooth motion.
     * duty≥1 → always; duty≤0 → never.
     */
    public static boolean dutyOnThisTick(long tick, double duty) {
        if (duty >= 1.0) {
            return true;
        }
        if (duty <= 0.0) {
            return false;
        }
        double prev = Math.floor((tick) * duty);
        double cur = Math.floor((tick + 1) * duty);
        return cur > prev;
    }

    /**
     * The Levitation amplitude to apply THIS tick to realise {@code targetRate}, blending between
     * amp0 ({@link #LEVITATION_AMP0_RATE}) and amp1 ({@link #LEVITATION_AMP1_RATE}) via a duty
     * cycle. <b>Always ≥ amp0</b> — the player is levitating every ascend tick, so gravity never
     * intrudes on an "off" tick (the reason we don't duty-cycle down to sub-amp0: that would be
     * jittery). A target at/below amp0 → always amp0; between amp0 and amp1 → duty-blend; at/above
     * amp1 → always amp1. Returns the integer amplifier (0 or 1).
     *
     * @return 0 to apply amp0 this tick, 1 to apply amp1 this tick
     */
    public static int amplitudeThisTick(long tick, double targetRate) {
        if (targetRate <= LEVITATION_AMP0_RATE) {
            return 0;
        }
        if (targetRate >= LEVITATION_AMP1_RATE) {
            return 1;
        }
        double blend = (targetRate - LEVITATION_AMP0_RATE) / (LEVITATION_AMP1_RATE - LEVITATION_AMP0_RATE);
        return dutyOnThisTick(tick, blend) ? 1 : 0;
    }

    /**
     * Effective floor rate the mod can actually deliver: {@code max(configFloor, amp0)}. Because
     * the ascend mechanism always levitates (never sub-amp0), the real floor is amp0 (~0.9),
     * clamped up if the config floor is somehow higher. Documented as the "usable, not a crawl"
     * tradeoff.
     */
    public static double effectiveFloor(double configFloorRate) {
        return Math.max(configFloorRate, LEVITATION_AMP0_RATE);
    }
}
