package com.k33bz.ropes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GSON-backed config, written to {@code config/ropes.json} on first run — the same
 * file-only pattern as postbox's {@link PostboxConfig sibling}. Every span/knob lives here
 * so behaviour can be tuned without recompiling. File-only for v1 (no live-set command).
 *
 * <p>NOTE: the segment/attachment records live in a SEPARATE store,
 * {@code config/ropes_store.json} ({@link RopeStore}). This file is knobs only.</p>
 */
public class RopesConfig {

    /**
     * Max straight-line span, in blocks, of a single rope segment. The 1.21.6 lead rework
     * (inherited by 26.x) snaps a leash past this distance; the spike measured 11 stable,
     * snap at 12. Keep this at or below 11 or the vanilla leash will silently break the rope.
     */
    public int maxSpanBlocks = 11;

    /**
     * Global cap on how many rope segments the store will hold. A permission-0, no-item-cost
     * {@code /rope tie} can otherwise be spammed into unbounded persistent endpoint entities and an
     * ever-growing store; ties beyond this cap (and exact-duplicate ties) are refused.
     */
    public int maxSegments = 2000;

    /**
     * Whether breaking either fence post of a segment drops a Rope back. On by default so the
     * material is recoverable; set false for a "ropes are consumed permanently" server.
     */
    public boolean dropRopeOnBreak = true;

    /**
     * Server-tick interval between store re-verification sweeps (re-anchor endpoints whose
     * leash was lost, cull orphans). 0 disables the periodic sweep (boot-time verify still runs).
     */
    public int verifyIntervalTicks = 200;

    /**
     * How close (blocks, eye to block centre) a survival player must be to BOTH posts to use
     * {@code /rope tie}; the command also costs one Rope from their inventory, exactly like the
     * right-click path. Ops (permission 2), creative players and the console are exempt. Default 6.
     */
    public double tieReachBlocks = 6.0;

    // --- decorative knot caps ---
    /**
     * Spawn small decorative {@code item_display} "knot" caps at every tie-point of a segment, so
     * the rope reads as deliberately tied rather than vanishing into a fence. On by default.
     */
    public boolean showKnots = true;

    /** Uniform scale of each knot cap ({@code item_display} scale; ~0.3–0.4 reads as a small knot). */
    public double knotScale = 0.35;

    /**
     * Optional player-head texture value (base64) for the knot cap. If blank (default), the cap is
     * a small {@code minecraft:lead} coil — pure vanilla, no external texture. Set this to a
     * "rope knot" head's texture value (e.g. from minecraft-heads.com, like postbox's mailbox
     * head) to render a themed knot skin instead.
     */
    public String knotHeadTexture = "";

    // --- rope climbing (v0.2.0) ---
    /** Master switch for the climb system. When off, ropes are decorative-only. */
    public boolean climbEnabled = true;

    /**
     * Minimum segment steepness (degrees above horizontal) that counts as climbable. Segments
     * flatter than this are purely aesthetic bridges — you can stand on / clip them but not climb.
     * Default 75°.
     */
    public double climbMinAngleDeg = 75.0;

    /**
     * How close (blocks, clamped point-to-segment distance) a player must be to a segment to be
     * "in contact" and able to climb it. Default 0.6.
     */
    public double climbReach = 0.6;

    /**
     * Look threshold (degrees). Looking UP by more than this (pitch &lt; -climbLookDeg) ascends;
     * looking level or down descends. Default 30°.
     */
    public double climbLookDeg = 30.0;

    /**
     * Ascend rate (blocks/second) at the gate angle ({@link #climbMinAngleDeg}) — the floor of the
     * angle&rarr;rate curve. Default 0.4. (See {@link ClimbRate}; the effect-duty-cycle
     * implementation may floor higher — documented at runtime.)
     */
    public double climbFloorRate = 0.4;

    /** Ascend rate (blocks/second) at a vertical (90°) segment — the top of the curve. Default 0.9. */
    public double climbVerticalRate = 0.9;

    /**
     * Hard cap (blocks/second) on ascend rate, regardless of angle. Default 1.8. MUST stay below
     * the vanilla ladder ascend (~2.35 b/s) — asserted in {@link ClimbRate} tests.
     */
    public double climbMaxRate = 1.8;

    /**
     * Reset the player's fall distance to 0 every tick while in contact and NOT sneaking, so
     * climbing accrues no fall damage. Releasing (sneak) or leaving contact stops the reset, so a
     * normal fall from the contact point deals damage. Default true.
     */
    public boolean climbResetFallWhileTouching = true;

    // --- climb-session logging (v0.3.0) ---
    /**
     * Emit one NDJSON line per completed climb session to {@link #climbLogDir}
     * ({@code ropes-climbs-YYYY-MM-DD.ndjson}), so the mc.kast.ro stats site can build climbing
     * leaderboards. Default true. When false, no climb writer is started (climbing itself is
     * unaffected).
     */
    public boolean climbLog = true;

    /**
     * Directory (relative to the run/game dir, or absolute) for the daily climb NDJSON files.
     * Default {@code "config/ropes_logs"}.
     */
    public String climbLogDir = "config/ropes_logs";

    /**
     * A climb session ends after this many consecutive ticks with NO climb contact (and no pending
     * release-fall). ~10 ticks (0.5s) tolerates a momentary slip off a rope without splitting one
     * climb into two sessions. Default 10.
     */
    public int climbSessionGraceTicks = 10;

    /**
     * Server-tick interval between climb-log flusher drains. The game thread only enqueues finished
     * sessions; every this-many ticks a scheduled drain writes them to the buffered writer and
     * flushes. 20 ≈ once/second. A clean shutdown always drains fully. Default 20.
     */
    public int climbLogFlushIntervalTicks = 20;

    // ------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ropes.json");
    }

    // Ranges sanitize() clamps to. Out-of-range or NaN values used to reach the tick unchecked.
    static final int MAX_SEGMENTS_CAP = 100_000;
    static final int TICKS_CAP = 72_000;            // one in-game hour of ticks at most
    static final double KNOT_SCALE_MIN = 0.05;
    static final double KNOT_SCALE_MAX = 4.0;
    static final double REACH_MIN = 0.1;
    static final double CLIMB_REACH_MAX = 3.0;
    static final double TIE_REACH_MAX = 16.0;
    /** Below the vanilla ladder ascend (~2.35 b/s), as ClimbRateTest asserts for the default. */
    static final double RATE_MAX = 2.3;

    public static RopesConfig load() {
        RopesConfig cfg = null;
        boolean unreadable = false;
        Path p = path();
        if (Files.exists(p)) {
            try {
                cfg = GSON.fromJson(Files.readString(p), RopesConfig.class);
            } catch (Exception e) {
                // Keep the broken file for the admin instead of saving defaults over their settings
                Path backup = RopeFiles.backUpCorrupt(p, System.currentTimeMillis());
                Ropes.LOGGER.error("[ropes] could not read config {}; using defaults (the file is kept as {})",
                        p, backup, e);
                unreadable = true;
            }
        }
        if (cfg == null) {
            cfg = new RopesConfig();
        }
        cfg.sanitize();
        if (!unreadable) {
            cfg.save(); // write back so new knobs appear in the file (never over an unreadable one)
        }
        return cfg;
    }

    /**
     * Bring every knob into a range the tick code can run on. A missing key keeps its default; a
     * NaN falls back to the default; anything else is clamped.
     */
    public void sanitize() {
        RopesConfig d = new RopesConfig();
        // >11 would let vanilla snap the leash and orphan the segment.
        maxSpanBlocks = RopeMath.clampMaxSpan(maxSpanBlocks);
        maxSegments = clamp(maxSegments, 0, MAX_SEGMENTS_CAP);
        verifyIntervalTicks = clamp(verifyIntervalTicks, 0, TICKS_CAP); // 0 = periodic sweep off
        tieReachBlocks = clamp(tieReachBlocks, 1.0, TIE_REACH_MAX, d.tieReachBlocks);
        knotScale = clamp(knotScale, KNOT_SCALE_MIN, KNOT_SCALE_MAX, d.knotScale);
        String tex = RopeChecks.cleanTexture(knotHeadTexture);
        if (knotHeadTexture != null && !knotHeadTexture.isBlank() && tex.isEmpty()) {
            Ropes.LOGGER.warn("[ropes] knotHeadTexture is not a base64 texture value; using the lead knot");
        }
        knotHeadTexture = tex;
        climbMinAngleDeg = clamp(climbMinAngleDeg, 0.0, 90.0, d.climbMinAngleDeg);
        climbReach = clamp(climbReach, REACH_MIN, CLIMB_REACH_MAX, d.climbReach);
        climbLookDeg = clamp(climbLookDeg, 0.0, 90.0, d.climbLookDeg);
        climbFloorRate = clamp(climbFloorRate, 0.0, RATE_MAX, d.climbFloorRate);
        climbVerticalRate = clamp(climbVerticalRate, 0.0, RATE_MAX, d.climbVerticalRate);
        climbMaxRate = clamp(climbMaxRate, 0.0, RATE_MAX, d.climbMaxRate);
        // Tolerate legacy/partial files: a blank dir would break the writer.
        if (climbLogDir == null || climbLogDir.isBlank()) {
            climbLogDir = d.climbLogDir;
        }
        climbSessionGraceTicks = clamp(climbSessionGraceTicks, 1, TICKS_CAP);
        climbLogFlushIntervalTicks = clamp(climbLogFlushIntervalTicks, 1, TICKS_CAP);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max, double fallback) {
        return Double.isNaN(v) ? fallback : Math.max(min, Math.min(max, v));
    }

    public void save() {
        try {
            RopeFiles.writeAtomically(path(), GSON.toJson(this));
        } catch (IOException e) {
            Ropes.LOGGER.warn("[ropes] could not save config", e);
        }
    }
}
