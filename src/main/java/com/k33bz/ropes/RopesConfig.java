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

    public static RopesConfig load() {
        RopesConfig cfg = null;
        try {
            if (Files.exists(path())) {
                cfg = GSON.fromJson(Files.readString(path()), RopesConfig.class);
            }
        } catch (Exception e) {
            Ropes.LOGGER.warn("[ropes] could not read config, using defaults", e);
        }
        if (cfg == null) {
            cfg = new RopesConfig();
        }
        // Clamp: >11 would let vanilla snap the leash and orphan the segment.
        cfg.maxSpanBlocks = RopeMath.clampMaxSpan(cfg.maxSpanBlocks);
        // Tolerate legacy/partial files: an absent field deserializes to its Java default, but a
        // blank/negative value that WOULD break the writer is repaired to a safe default here.
        if (cfg.climbLogDir == null || cfg.climbLogDir.isBlank()) {
            cfg.climbLogDir = "config/ropes_logs";
        }
        if (cfg.climbSessionGraceTicks < 1) {
            cfg.climbSessionGraceTicks = 1;
        }
        if (cfg.climbLogFlushIntervalTicks < 1) {
            cfg.climbLogFlushIntervalTicks = 1;
        }
        cfg.save(); // write back so new knobs appear in the file
        return cfg;
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            Ropes.LOGGER.warn("[ropes] could not save config", e);
        }
    }
}
