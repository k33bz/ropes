package com.k33bz.ropes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the climb-session accumulator (v0.3.0) — no server needed, runs in CI. Each
 * asserts a specific correctness property so it fails (red) if the accumulation is wrong: that
 * {@code up}/{@code down} sum the right deltas, and especially that {@code peak_climb} tracks the
 * LONGEST CONTINUOUS ascent run — resetting on any descent or pause and taking the max — rather
 * than total ascent.
 */
class ClimbSessionTest {

    private static final double EPS = 1e-9;

    @Test
    void upSumsPositiveDeltas() {
        ClimbSession s = new ClimbSession("minecraft:overworld");
        s.climbTick(0.5);
        s.climbTick(0.9);
        s.climbTick(0.9);
        assertEquals(2.3, s.up(), EPS);
        assertEquals(0.0, s.down(), EPS);
    }

    @Test
    void downSumsDescentMagnitudes() {
        ClimbSession s = new ClimbSession("d");
        s.descendTick(-0.4);
        s.descendTick(-0.4);
        assertEquals(0.8, s.down(), EPS, "descent accumulates the absolute value of negative Δy");
        assertEquals(0.0, s.up(), EPS);
    }

    @Test
    void peakClimbIsLongestContinuousRunNotTotal() {
        ClimbSession s = new ClimbSession("d");
        // Run A: +3 total over three ticks.
        s.climbTick(1.0);
        s.climbTick(1.0);
        s.climbTick(1.0);
        // A descent breaks the run.
        s.descendTick(-2.0);
        // Run B: +2 total — shorter than run A.
        s.climbTick(1.0);
        s.climbTick(1.0);
        assertEquals(5.0, s.up(), EPS, "up is the total of all ascent");
        assertEquals(2.0, s.down(), EPS);
        assertEquals(3.0, s.peakClimb(), EPS, "peak_climb is the longest single run (3), not the total (5)");
    }

    @Test
    void peakClimbTakesTheMaxWhenALaterRunIsLonger() {
        ClimbSession s = new ClimbSession("d");
        s.climbTick(1.0);           // run A = 1
        s.descendTick(-0.5);        // break
        s.climbTick(1.0);
        s.climbTick(1.0);
        s.climbTick(1.0);           // run B = 3 (new max)
        assertEquals(3.0, s.peakClimb(), EPS);
    }

    @Test
    void pauseTickBreaksTheContinuousRun() {
        ClimbSession s = new ClimbSession("d");
        s.climbTick(1.0);
        s.climbTick(1.0);           // run = 2
        s.climbTick(0.0);           // headroom gate / pause → run resets
        s.climbTick(1.0);           // run = 1
        assertEquals(2.0, s.peakClimb(), EPS, "a zero-Δy pause resets the run; earlier run (2) stands");
        assertEquals(3.0, s.up(), EPS, "up still totals all positive ascent");
    }

    @Test
    void aNegativeClimbTickCountsAsDescentAndBreaksTheRun() {
        ClimbSession s = new ClimbSession("d");
        s.climbTick(2.0);           // run = 2
        s.climbTick(-1.0);          // a dip while "climbing": counts as descent, breaks run
        assertEquals(2.0, s.peakClimb(), EPS);
        assertEquals(2.0, s.up(), EPS);
        assertEquals(1.0, s.down(), EPS);
    }

    @Test
    void releaseFallOnlyCountsAfterARelease() {
        ClimbSession s = new ClimbSession("d");
        s.climbTick(3.0);
        // Landed WITHOUT releasing (walked off the top) → no release_fall.
        s.land(5.0);
        assertEquals(0.0, s.releaseFall(), EPS, "a landing before any release contributes nothing");

        s.markReleased();
        s.land(7.25);
        assertEquals(7.25, s.releaseFall(), EPS, "after release, the landing fall distance is recorded");
    }

    @Test
    void releaseFallTakesTheLargerOfMultipleLandings() {
        ClimbSession s = new ClimbSession("d");
        s.markReleased();
        s.land(4.0);
        s.land(9.5);
        s.land(2.0);
        assertEquals(9.5, s.releaseFall(), EPS, "the biggest post-release drop wins");
    }

    @Test
    void markReleasedBreaksTheAscentRun() {
        ClimbSession s = new ClimbSession("d");
        s.climbTick(2.0);           // run = 2
        s.markReleased();
        s.climbTick(1.0);           // new run = 1
        assertEquals(2.0, s.peakClimb(), EPS);
    }

    @Test
    void hasMovementIsFalseForAZeroLengthSession() {
        ClimbSession s = new ClimbSession("d");
        assertFalse(s.hasMovement(), "a brush against a rope with no movement is skippable");
        s.climbTick(0.0);           // a pure pause is still no movement
        assertFalse(s.hasMovement());
    }

    @Test
    void hasMovementIsTrueOnceAnythingMoves() {
        ClimbSession up = new ClimbSession("d");
        up.climbTick(0.1);
        assertTrue(up.hasMovement());

        ClimbSession down = new ClimbSession("d");
        down.descendTick(-0.1);
        assertTrue(down.hasMovement());

        ClimbSession fell = new ClimbSession("d");
        fell.markReleased();
        fell.land(1.0);
        assertTrue(fell.hasMovement(), "a pure release-and-fall session is worth logging");
    }

    @Test
    void dimIsCarriedThrough() {
        ClimbSession s = new ClimbSession("minecraft:the_nether");
        assertEquals("minecraft:the_nether", s.dim());
    }

    @Test
    void realisticAscendPausedescendReleaseFallScenario() {
        // A player climbs 8 blocks, gets ceiling-gated for a tick, climbs 4 more, drifts down 2,
        // then sneak-releases and falls 6 to the ground.
        ClimbSession s = new ClimbSession("minecraft:overworld");
        for (int i = 0; i < 8; i++) {
            s.climbTick(1.0);       // run reaches 8
        }
        s.climbTick(0.0);           // pause (headroom) → run resets, but 8 is banked as peak
        for (int i = 0; i < 4; i++) {
            s.climbTick(1.0);       // run reaches 4
        }
        s.descendTick(-1.0);
        s.descendTick(-1.0);        // down += 2
        s.markReleased();
        s.land(6.0);

        assertEquals(12.0, s.up(), EPS, "8 + 4 ascended");
        assertEquals(2.0, s.down(), EPS);
        assertEquals(8.0, s.peakClimb(), EPS, "longest single run was the first 8, not the 12 total");
        assertEquals(6.0, s.releaseFall(), EPS);
        assertTrue(s.hasMovement());
    }
}
