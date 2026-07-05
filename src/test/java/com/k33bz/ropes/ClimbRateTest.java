package com.k33bz.ropes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the climb angle&rarr;rate curve and the Levitation duty cycle (k33bz
 * decision #1: angle scales rate off a fixed floor). No server needed — runs in CI.
 */
class ClimbRateTest {

    // Defaults matching RopesConfig.
    private static final double GATE = 75.0;
    private static final double FLOOR = 0.4;
    private static final double VERT = 0.9;
    private static final double CAP = 1.8;

    private static double rate(double deg) {
        return ClimbRate.targetRate(deg, GATE, FLOOR, VERT, CAP);
    }

    @Test
    void belowGateIsNotClimbable() {
        assertEquals(0.0, rate(0.0), 1e-9);
        assertEquals(0.0, rate(45.0), 1e-9, "45° is aesthetic-only, not climbable");
        assertEquals(0.0, rate(74.9), 1e-9);
    }

    @Test
    void floorAtGateAngle() {
        assertEquals(FLOOR, rate(GATE), 1e-9, "rate at the gate angle is exactly the floor");
    }

    @Test
    void verticalHitsVerticalRate() {
        assertEquals(VERT, rate(90.0), 1e-9, "rate at 90° is the vertical rate");
    }

    @Test
    void monotonicIncreasingWithAngle() {
        double prev = -1.0;
        for (double deg = GATE; deg <= 90.0; deg += 1.0) {
            double r = rate(deg);
            assertTrue(r >= prev, "rate must not decrease as angle increases (at " + deg + "°)");
            prev = r;
        }
    }

    @Test
    void steeperIsStrictlyFaster() {
        assertTrue(rate(90.0) > rate(82.0), "vertical faster than 82°");
        assertTrue(rate(82.0) > rate(75.0), "82° faster than the gate");
    }

    @Test
    void hardCapClampsRate() {
        // A silly-high vertical rate must still clamp to the cap.
        double capped = ClimbRate.targetRate(90.0, GATE, FLOOR, /* verticalRate */ 5.0, CAP);
        assertEquals(CAP, capped, 1e-9);
    }

    @Test
    void everyRateStaysBelowLadder() {
        // The whole curve (and the cap) must stay under vanilla ladder ascend (~2.35 b/s).
        assertTrue(CAP < ClimbRate.LADDER_ASCEND, "config cap must be below ladder ascend");
        for (double deg = GATE; deg <= 90.0; deg += 0.5) {
            assertTrue(rate(deg) < ClimbRate.LADDER_ASCEND,
                    "rate at " + deg + "° must be below ladder ascend");
        }
        // Even a mis-set vertical rate above the ladder is clamped by the cap below it.
        assertTrue(ClimbRate.targetRate(90.0, GATE, FLOOR, 10.0, CAP) < ClimbRate.LADDER_ASCEND);
    }

    // --- duty cycle ---

    @Test
    void dutyFractionMapsTargetOverAmp0() {
        assertEquals(0.4 / 0.9, ClimbRate.duty(0.4, 0.9), 1e-9);
        assertEquals(1.0, ClimbRate.duty(0.9, 0.9), 1e-9, "target == amp0 → full duty");
        assertEquals(1.0, ClimbRate.duty(1.5, 0.9), 1e-9, "target above amp0 → clamped full duty");
        assertEquals(0.0, ClimbRate.duty(0.0, 0.9), 1e-9);
    }

    @Test
    void dutyCycleAveragesToFraction() {
        // Over a long window, the fraction of on-ticks must approximate the duty fraction.
        double duty = 0.4 / 0.9; // ~0.444
        int on = 0;
        int n = 9000;
        for (long t = 0; t < n; t++) {
            if (ClimbRate.dutyOnThisTick(t, duty)) {
                on++;
            }
        }
        double frac = (double) on / n;
        assertEquals(duty, frac, 0.01, "duty-cycle on-fraction must track the target duty");
    }

    @Test
    void dutyEdgesAreAllOrNothing() {
        assertTrue(ClimbRate.dutyOnThisTick(123, 1.0));
        assertTrue(ClimbRate.dutyOnThisTick(0, 2.0));
        assertFalse(ClimbRate.dutyOnThisTick(123, 0.0));
        assertFalse(ClimbRate.dutyOnThisTick(0, -1.0));
    }

    @Test
    void dutyCycleIsEvenlySpreadNotBunched() {
        // At duty 0.5, on-ticks should alternate roughly every other tick (no long gaps).
        int maxGap = 0, gap = 0;
        for (long t = 0; t < 100; t++) {
            if (ClimbRate.dutyOnThisTick(t, 0.5)) {
                maxGap = Math.max(maxGap, gap);
                gap = 0;
            } else {
                gap++;
            }
        }
        assertTrue(maxGap <= 1, "at 50% duty no gap between on-ticks should exceed 1 (got " + maxGap + ")");
    }

    // --- amp0/amp1 blend (the delivered mechanism: always levitating, never sub-amp0) ---

    @Test
    void effectiveFloorIsAtLeastAmp0() {
        // Config floor 0.4 is below amp0; the deliverable floor clamps up to amp0 (~0.9).
        assertEquals(ClimbRate.LEVITATION_AMP0_RATE, ClimbRate.effectiveFloor(0.4), 1e-9);
        // A config floor above amp0 is honored.
        assertEquals(1.2, ClimbRate.effectiveFloor(1.2), 1e-9);
    }

    @Test
    void amplitudeIsZeroAtOrBelowAmp0() {
        for (long t = 0; t < 20; t++) {
            assertEquals(0, ClimbRate.amplitudeThisTick(t, ClimbRate.LEVITATION_AMP0_RATE));
            assertEquals(0, ClimbRate.amplitudeThisTick(t, 0.5));
        }
    }

    @Test
    void amplitudeIsOneAtOrAboveAmp1() {
        for (long t = 0; t < 20; t++) {
            assertEquals(1, ClimbRate.amplitudeThisTick(t, ClimbRate.LEVITATION_AMP1_RATE));
            assertEquals(1, ClimbRate.amplitudeThisTick(t, 5.0));
        }
    }

    @Test
    void amplitudeBlendAveragesToTargetBetweenAmp0AndAmp1() {
        // A target midway between amp0 and amp1 should apply amp1 ~half the ticks.
        double target = (ClimbRate.LEVITATION_AMP0_RATE + ClimbRate.LEVITATION_AMP1_RATE) / 2;
        int amp1Ticks = 0, n = 9000;
        for (long t = 0; t < n; t++) {
            if (ClimbRate.amplitudeThisTick(t, target) == 1) {
                amp1Ticks++;
            }
        }
        // Expected on-fraction ≈ (target-amp0)/(amp1-amp0) = 0.5; the delivered average rate is
        // then amp0 + 0.5*(amp1-amp0) = target.
        double frac = (double) amp1Ticks / n;
        assertEquals(0.5, frac, 0.01);
        double deliveredRate = ClimbRate.LEVITATION_AMP0_RATE
                + frac * (ClimbRate.LEVITATION_AMP1_RATE - ClimbRate.LEVITATION_AMP0_RATE);
        assertEquals(target, deliveredRate, 0.02);
    }

    @Test
    void deliveredRatesAllStayBelowLadder() {
        // amp0, amp1, and everything the blend can deliver must stay under the ladder.
        assertTrue(ClimbRate.LEVITATION_AMP0_RATE < ClimbRate.LADDER_ASCEND);
        assertTrue(ClimbRate.LEVITATION_AMP1_RATE < ClimbRate.LADDER_ASCEND);
    }
}
