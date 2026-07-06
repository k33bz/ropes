package com.k33bz.ropes;

/**
 * The pure, game-runtime-free accumulator for one player's climb <b>session</b> — everything the
 * climb log needs, computed from a stream of per-tick vertical deltas plus a few state signals. No
 * Minecraft or filesystem dependency, so the whole accumulation model is unit-tested in CI (the
 * CoE-red-provable core of the v0.3.0 climb log). {@link RopeClimb} owns one of these per active
 * session and feeds it the signals it derives from the game state each tick; on session end it
 * hands the totals to {@link ClimbLog#toJson}.
 *
 * <p>A <b>session</b> begins at the first rope-climb <em>contact</em> and ends on any of:
 * sneak-release, no rope-climb contact for {@code graceTicks} ticks, death, dimension change, or
 * disconnect (that policy lives in {@link RopeClimb}; this class just accumulates). The four
 * measured quantities:</p>
 *
 * <ul>
 *   <li><b>up</b> — sum of positive Δy while climbing (blocks ascended).</li>
 *   <li><b>down</b> — sum of |negative Δy| while on the rope / slow-falling (blocks descended).</li>
 *   <li><b>peak_climb</b> — the <em>longest continuous ascent run</em>: a running positive-Δy
 *       counter that resets to 0 on any descent or pause (a non-positive climb tick), tracking the
 *       max run seen. This is NOT total {@code up} — it is the single best unbroken climb.</li>
 *   <li><b>release_fall</b> — the vanilla fall distance actually fallen AFTER a sneak-release, read
 *       at the landing tick (clean, because climbing resets {@code fallDistance} continuously). 0 if
 *       the player left normally / landed back on the rope path / on water.</li>
 * </ul>
 *
 * <p>All fields are plain {@code double} block counts; {@link RopeClimb} converts effect-driven
 * motion into these deltas. The accumulator never itself decides units — it sums exactly what it is
 * given.</p>
 */
public final class ClimbSession {

    private final String dim;

    private double up;
    private double down;
    private double peakClimb;

    /** The current unbroken ascent run; folded into {@link #peakClimb} and reset on descent/pause. */
    private double currentRun;

    private double releaseFall;

    /** True once the player sneak-released in this session (a real fall may follow). */
    private boolean released;

    public ClimbSession(String dim) {
        this.dim = dim;
    }

    /**
     * A climbing tick: {@code dy} is the vertical delta (blocks) this tick while ascending on the
     * rope. Positive Δy extends the current ascent run and adds to {@code up}; a non-positive Δy
     * (held in place by a ceiling gate, or a momentary dip) breaks the run — the ascent run resets
     * to 0 so {@code peak_climb} only ever counts a genuinely continuous climb. A negative Δy while
     * "climbing" also counts as descent (added to {@code down}) so totals stay consistent.
     */
    public void climbTick(double dy) {
        if (dy > 0.0) {
            up += dy;
            currentRun += dy;
            if (currentRun > peakClimb) {
                peakClimb = currentRun;
            }
        } else {
            // Pause (dy==0, e.g. headroom gate) or dip: the continuous run ends.
            currentRun = 0.0;
            if (dy < 0.0) {
                down += -dy;
            }
        }
    }

    /**
     * A descend / slow-fall tick while still on the rope: negative Δy adds to {@code down}; any
     * descent also breaks the ascent run. A non-negative Δy here (drifting up on a rope while
     * looking down is not possible, but be defensive) is treated as no descent and still breaks the
     * run.
     */
    public void descendTick(double dy) {
        currentRun = 0.0;
        if (dy < 0.0) {
            down += -dy;
        }
    }

    /** Mark that the player sneak-released this tick — the ascent run ends and a real fall may begin. */
    public void markReleased() {
        released = true;
        currentRun = 0.0;
    }

    /**
     * Record the landing fall distance. Only counts as {@code release_fall} if the player had
     * released (so a normal walk-off the top of a rope, or landing back on the rope, contributes 0).
     * Takes the max so a re-entered fall in the same session reports the biggest drop.
     */
    public void land(double fallDistance) {
        if (released && fallDistance > releaseFall) {
            releaseFall = fallDistance;
        }
    }

    public boolean isReleased() {
        return released;
    }

    /**
     * Whether the session saw any net movement worth logging. Zero-length sessions (a brush against
     * a rope with no ascent, descent, or release-fall) may be skipped by the caller.
     */
    public boolean hasMovement() {
        return up > 0.0 || down > 0.0 || releaseFall > 0.0;
    }

    public String dim() {
        return dim;
    }

    public double up() {
        return up;
    }

    public double down() {
        return down;
    }

    public double peakClimb() {
        return peakClimb;
    }

    public double releaseFall() {
        return releaseFall;
    }
}
