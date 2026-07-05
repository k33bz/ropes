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

import java.util.HashSet;
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

    /** Called every server tick from {@link Ropes}. */
    public static void tick(MinecraftServer server) {
        if (!Ropes.CONFIG.climbEnabled) {
            return;
        }
        tickCounter++;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tickPlayer(player);
        }
    }

    private static void tickPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        if (player.isCreative() || player.isSpectator() || !(player.level() instanceof ServerLevel level)) {
            leaveContact(player, id);
            return;
        }
        RopesConfig cfg = Ropes.CONFIG;
        String dim = level.dimension().identifier().toString();
        Vec3 pos = player.position();
        double eyeY = player.getEyeY();
        // Query the registry with the player's mid-body point (no entity scan).
        double midY = (pos.y + eyeY) * 0.5;
        RopeStore.Segment seg = RopeRegistry.nearestClimbable(
                dim, pos.x, midY, pos.z, cfg.climbReach, Math.toRadians(cfg.climbMinAngleDeg));
        if (seg == null) {
            leaveContact(player, id);
            return;
        }

        // In contact. Decide action from look + sneak.
        if (player.isShiftKeyDown()) {
            // RELEASE: apply nothing; STOP resetting fall distance (a real fall begins here).
            removeOurLevitation(player, id);
            return;
        }

        // Fall-damage: reset every contact tick while not sneaking (handled above).
        if (cfg.climbResetFallWhileTouching) {
            player.resetFallDistance();
        }

        float pitch = player.getXRot(); // negative = looking up
        boolean ascend = pitch < -cfg.climbLookDeg;
        if (ascend) {
            ascend(player, id, level, seg, cfg);
        } else {
            descend(player, id);
        }
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
}
