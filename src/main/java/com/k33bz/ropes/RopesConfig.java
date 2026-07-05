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
