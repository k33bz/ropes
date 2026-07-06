package com.k33bz.ropes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rope climbing — status-effect driven (client-predicted, no {@code setDeltaMovement} rubber-band),
 * ticked once per server tick. Exactly the design k33bz locked:
 *
 * <ul>
 *   <li><b>Contact</b> is found via {@link RopeRegistry#nearestClimbable} — NO entity scanning,
 *       just arithmetic over stored endpoints: within {@code climbReach} of a segment whose slope
 *       is ≥ {@code climbMinAngleDeg} (below-gate segments are aesthetic, not climbable).</li>
 *   <li><b>Look/sneak → action</b> (treading-water, no hover):
 *       looking up past {@code climbLookDeg} → <b>ascend</b> (Levitation); level/down →
 *       <b>descend</b> (SlowFalling drift); sneak → <b>release</b> (apply nothing, stop resetting
 *       fall distance so a real fall begins).</li>
 *   <li>Effects are applied HIDDEN ({@code showParticles=false, showIcon=false}) with a short
 *       duration re-applied each contact tick, so it looks like natural climbing and self-clears
 *       ~2 ticks after leaving. On the transition OUT of contact we actively remove Levitation so
 *       the player stops at a ledge instead of overshooting.</li>
 *   <li><b>Rate</b> scales with angle off a fixed floor ({@link ClimbRate}); ascend uses a
 *       Levitation-amp0 duty cycle to hit sub-amp0 targets smoothly.</li>
 *   <li><b>Headroom suffocation gate</b> (non-configurable): before an ascend tick, if the block
 *       the player would rise into is solid, skip the levitation this tick (hold in place).</li>
 *   <li><b>Fall damage</b>: reset fall distance every contact tick unless sneaking; releasing or
 *       leaving contact stops the reset so the fall from the contact point deals normal damage.</li>
 * </ul>
 *
 * <p><b>v0.3.0 climb-session logging.</b> Per active session (from first climb contact to session
 * end) this tick loop feeds the measured vertical deltas into a pure {@link ClimbSession}
 * accumulator ({@code up}/{@code down}/{@code peak_climb}/{@code release_fall}); on session end it
 * emits one NDJSON line to {@code config/ropes_logs/ropes-climbs-YYYY-MM-DD.ndjson} via
 * {@link ClimbLogWriter}. A session ends on any of: sneak-release (then a landing tick captures
 * {@code release_fall}), no climb contact for {@code climbSessionGraceTicks}, death, dimension
 * change, or disconnect. Δy is the player's real per-tick Y delta while tracked — effect-driven
 * ascent/slow-fall shows up as clean deltas because the accumulator sums exactly what it is fed.</p>
 */
public final class RopeClimb {
    private RopeClimb() {
    }

    /** Short re-applied duration (ticks) so the effect self-clears ~2 ticks after leaving contact. */
    private static final int EFFECT_TICKS = 3;

    /** Players who were ascending (had our Levitation) last tick — used for the leave-transition removal. */
    private static final Set<UUID> WAS_LEVITATING = new HashSet<>();

    /** Monotonic tick counter for the duty cycle. */
    private static long tickCounter = 0;

    // ---- climb-session logging state (v0.3.0) ----

    /** Per-player live session state; an entry exists only while a session is open. */
    private static final Map<UUID, State> SESSIONS = new HashMap<>();

    /** Live per-player session bookkeeping: the pure accumulator + the tick-to-tick signals. */
    private static final class State {
        final ClimbSession session;
        final String name;     // captured at session start (survives a mid-fall disconnect)
        double lastY;          // player Y at the previous tracked tick (for Δy)
        int graceTicks;        // ticks since last climb contact (drives the grace timeout)
        boolean awaitingLand;  // player released and is falling; watching for the landing tick

        State(String dim, String name, double y) {
            this.session = new ClimbSession(dim);
            this.name = name;
            this.lastY = y;
        }
    }

    /** Called every server tick from {@link Ropes}. */
    public static void tick(MinecraftServer server) {
        if (!Ropes.CONFIG.climbEnabled) {
            // If disabled mid-run, flush any open sessions so nothing is lost.
            if (!SESSIONS.isEmpty()) {
                endAllOpenSessions();
            }
            return;
        }
        tickCounter++;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            tickPlayer(player);
        }
        // Disconnected players still holding an open session → end it (best-effort, no fall capture).
        for (Iterator<Map.Entry<UUID, State>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, State> e = it.next();
            if (!online.contains(e.getKey())) {
                endSession(e.getKey(), e.getValue());
                it.remove();
            }
        }
    }

    private static void tickPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        if (player.isCreative() || player.isSpectator() || !(player.level() instanceof ServerLevel level)) {
            endSessionIfOpen(id); // dimension-load transitions, mode swaps, etc.
            leaveContact(player, id);
            return;
        }
        RopesConfig cfg = Ropes.CONFIG;
        String dim = level.dimension().identifier().toString();
        Vec3 pos = player.position();
        double eyeY = player.getEyeY();
        double midY = (pos.y + eyeY) * 0.5;
        RopeStore.Segment seg = RopeRegistry.nearestClimbable(
                dim, pos.x, midY, pos.z, cfg.climbReach, Math.toRadians(cfg.climbMinAngleDeg));

        State st = SESSIONS.get(id);

        // Dimension change ends the current session (and starts fresh if still climbing there).
        if (st != null && !st.session.dim().equals(dim)) {
            endSession(id, st);
            SESSIONS.remove(id);
            st = null;
        }

        if (seg == null) {
            // No climb contact. If a session is open, either we're capturing a release-fall or the
            // grace timer is running down.
            if (st != null) {
                double dy = updateDeltaAndReturn(st, player.getY());
                if (st.awaitingLand) {
                    // Falling after a sneak-release: land when we hit ground/water (rope handled above).
                    if (hasLanded(player)) {
                        st.session.land(player.fallDistance);
                        endSession(id, st);
                        SESSIONS.remove(id);
                    }
                    // else: still falling; keep the session open until landing (or death → below).
                } else {
                    // On the rope path but momentarily off-contact: count any downward drift, then
                    // run the grace timer. dy<0 here is a slip/descent; dy>=0 is a pause.
                    st.session.descendTick(dy);
                    if (++st.graceTicks >= Math.max(1, cfg.climbSessionGraceTicks)) {
                        endSession(id, st);
                        SESSIONS.remove(id);
                    }
                }
                // Death while a session is open (falling or grace): flush and drop.
                if (player.isDeadOrDying()) {
                    endSessionIfOpen(id);
                }
            }
            leaveContact(player, id);
            return;
        }

        // In contact with a climbable rope.
        if (st == null) {
            st = new State(dim, player.getGameProfile().name(), player.getY());
            SESSIONS.put(id, st);
        }
        st.graceTicks = 0;
        st.awaitingLand = false; // re-contacting a rope ends any pending release-fall watch cleanly
        double dy = updateDeltaAndReturn(st, player.getY());

        if (player.isDeadOrDying()) {
            endSession(id, st);
            SESSIONS.remove(id);
            leaveContact(player, id);
            return;
        }

        if (player.isShiftKeyDown()) {
            // RELEASE: apply nothing; STOP resetting fall distance (a real fall begins here). Keep
            // the session open and start watching for the landing tick to read release_fall.
            removeOurLevitation(player, id);
            st.session.markReleased();
            st.awaitingLand = true;
            return;
        }

        // Fall-damage: reset every contact tick while not sneaking.
        if (cfg.climbResetFallWhileTouching) {
            player.resetFallDistance();
        }

        float pitch = player.getXRot(); // negative = looking up
        boolean ascend = pitch < -cfg.climbLookDeg;
        if (ascend) {
            st.session.climbTick(dy);
            ascend(player, id, level, seg, cfg);
        } else {
            st.session.descendTick(dy);
            descend(player, id);
        }
    }

    /** Update the stored last-Y and return the Δy for this tick. */
    private static double updateDeltaAndReturn(State st, double y) {
        double dy = y - st.lastY;
        st.lastY = y;
        return dy;
    }

    /**
     * Landed for release-fall purposes: standing on ground, in/at water, or re-touching a climbable
     * rope (the caller handles the rope case by clearing {@code awaitingLand} on contact, so here we
     * only need ground/water). {@code onGround()} is the authoritative "supported" flag.
     */
    private static boolean hasLanded(ServerPlayer player) {
        return player.onGround() || player.isInWater() || player.isInLava();
    }

    private static void ascend(ServerPlayer player, UUID id, ServerLevel level,
                               RopeStore.Segment seg, RopesConfig cfg) {
        // Headroom suffocation gate (non-configurable): don't force the player up into a ceiling.
        if (headBlocked(player, level)) {
            // Hold in place: no levitation this tick. Clear any lingering slow-fall from a prior descend.
            removeOurLevitation(player, id);
            player.removeEffect(MobEffects.SLOW_FALLING);
            return;
        }
        double angleDeg = Math.toDegrees(RopeRegistry.geometryOf(seg).pitch());
        // Deliverable curve: floor at the effective floor (continuous amp0, ~0.9) at the gate,
        // scaling up to the config cap (default 1.8 ≈ amp1) at vertical. We floor at amp0 rather
        // than the 0.4 config value because the ascend mechanism ALWAYS levitates (never sub-amp0)
        // so gravity never intrudes on an off-tick — the "usable, not a crawl" fallback the design
        // allows. Angle still scales the rate up toward the cap (steeper = faster).
        double deliverableFloor = ClimbRate.effectiveFloor(cfg.climbFloorRate);
        double target = ClimbRate.targetRate(angleDeg, cfg.climbMinAngleDeg,
                deliverableFloor, cfg.climbMaxRate, cfg.climbMaxRate);
        int amp = ClimbRate.amplitudeThisTick(tickCounter, target);
        // Never descend while trying to climb: clear any slow-fall, then levitate this tick.
        player.removeEffect(MobEffects.SLOW_FALLING);
        applyHidden(player, MobEffects.LEVITATION, amp);
        WAS_LEVITATING.add(id);
    }

    private static void descend(ServerPlayer player, UUID id) {
        // DESCEND: SlowFalling drift. Clear any ascend levitation so we actually come down.
        removeOurLevitation(player, id);
        applyHidden(player, MobEffects.SLOW_FALLING, 0);
    }

    /** Leaving contact entirely: remove our levitation (stop overshoot at a ledge), let slow-fall lapse. */
    private static void leaveContact(ServerPlayer player, UUID id) {
        removeOurLevitation(player, id);
        // SlowFalling is harmless if it lingers a tick or two; its short duration self-clears.
    }

    /** Remove Levitation only if WE applied it this session (transition-out at a ledge). */
    private static void removeOurLevitation(ServerPlayer player, UUID id) {
        if (WAS_LEVITATING.remove(id) || player.hasEffect(MobEffects.LEVITATION)) {
            player.removeEffect(MobEffects.LEVITATION);
        }
    }

    /** Apply a hidden (no particles, no HUD icon), short, re-applied effect. */
    private static void applyHidden(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        player.addEffect(new MobEffectInstance(effect, EFFECT_TICKS, amplifier,
                /* ambient */ false, /* showParticles */ false, /* showIcon */ false));
    }

    /**
     * Headroom gate: is the block the player's head would rise into solid? Checks the block at the
     * player's eye level and the one above it. Uses {@code blocksMotion()} (true for solid
     * collidable blocks) — the definitive "would I suffocate / be blocked rising" test.
     */
    private static boolean headBlocked(ServerPlayer player, ServerLevel level) {
        BlockPos head = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        BlockPos above = head.above();
        return blocks(level, head) || blocks(level, above);
    }

    private static boolean blocks(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.blocksMotion();
    }

    // ------------------------------------------------------------ session end

    /** End + flush an open session for this player if one exists; no-op otherwise. */
    private static void endSessionIfOpen(UUID id) {
        State st = SESSIONS.remove(id);
        if (st != null) {
            endSession(id, st);
        }
    }

    /**
     * Finalize one session: hand its totals to the writer as a single NDJSON line. Zero-movement
     * sessions (a brush against a rope with no ascent/descent/release-fall) are skipped. Never
     * throws — a null writer (logging disabled) or serialization issue must not disturb the tick.
     */
    private static void endSession(UUID id, State st) {
        try {
            ClimbLogWriter w = Ropes.CLIMB_WRITER;
            if (w == null || !st.session.hasMovement()) {
                return;
            }
            ClimbLog.Session line = new ClimbLog.Session(
                    System.currentTimeMillis(),
                    st.name,
                    id.toString(),
                    round(st.session.up()),
                    round(st.session.down()),
                    round(st.session.peakClimb()),
                    round(st.session.releaseFall()),
                    st.session.dim());
            w.enqueue(line);
        } catch (Exception ex) {
            Ropes.LOGGER.warn("[ropes] failed to emit climb session", ex);
        }
    }

    /** Flush every open session (called when climbing is disabled mid-run or at an odd state). */
    private static void endAllOpenSessions() {
        for (Map.Entry<UUID, State> e : SESSIONS.entrySet()) {
            endSession(e.getKey(), e.getValue());
        }
        SESSIONS.clear();
    }

    /** Round a block count to 3 decimals so the NDJSON stays compact (12.5, not 12.499999). */
    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
