package com.k33bz.ropes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The moving end of a rope segment. The spike proved a rope cannot be made from two static knots
 * (a {@code leash_knot} is a HOLDER, not leashable) — so each segment is a <b>fence knot &rarr;
 * invisible leashable mob</b> pair, and vanilla's leash renderer draws the line between them.
 *
 * <p>The endpoint is a <b>bat</b> (small, leashable — confirmed in the spike over allay/pig/
 * chicken) made {@code Invisible / NoGravity / Silent / NoAI / PersistenceRequired} and pinned in
 * place, so it never wanders, sinks, makes noise, or despawns. It sits AT fence B and is leashed
 * to fence A's knot; the leash is the visible rope.</p>
 */
public final class RopeEndpoint {
    private RopeEndpoint() {
    }

    /** Tag on every rope endpoint mob, so a stray one is identifiable/cullable. */
    public static final String TAG = "ropes_endpoint";

    /**
     * Spawn a pinned invisible endpoint bat at the centre of {@code fenceB}, tagged and leashed
     * to {@code knotA}. Retries the {@link Leashable#setLeashedTo attach} until a read-back
     * confirms {@code getLeashHolder() == knotA} (the spike warned an attach can silently no-op
     * if entities aren't loaded that tick). Returns the endpoint's UUID, or {@code null} if the
     * leash could not be confirmed after all retries (caller must not consume a Rope then).
     */
    public static UUID spawnLeashed(ServerLevel level, BlockPos fenceB, LeashFenceKnotEntity knotA) {
        // create(Level, EntitySpawnReason) then position via pin()/snapTo — the 3-arg
        // (ServerLevel, BlockPos, reason) overload resolves ambiguously against the ValueInput
        // spawn family in 26.x, so we use the unambiguous 2-arg factory.
        Entity e = EntityTypes.BAT.create(level, EntitySpawnReason.COMMAND);
        if (!(e instanceof Bat bat)) {
            Ropes.LOGGER.warn("[ropes] could not create endpoint bat at {}", fenceB.toShortString());
            return null;
        }
        pin(bat, fenceB);
        if (!level.addFreshEntity(bat)) {
            Ropes.LOGGER.warn("[ropes] world rejected endpoint bat at {}", fenceB.toShortString());
            bat.discard();
            return null;
        }
        if (!attachConfirmed(bat, knotA)) {
            Ropes.LOGGER.warn("[ropes] leash attach could not be confirmed for endpoint {}", bat.getUUID());
            bat.discard();
            return null;
        }
        return bat.getUUID();
    }

    /** Make a bat a silent, pinned, invisible, undespawnable rope end at {@code pos}. */
    public static void pin(Bat bat, BlockPos pos) {
        bat.setInvisible(true);
        bat.setNoGravity(true);
        bat.setSilent(true);
        bat.setNoAi(true);
        bat.setPersistenceRequired();
        bat.setInvulnerable(true);
        bat.setResting(false);
        bat.setCustomName(Component.literal("Rope"));
        bat.setCustomNameVisible(false);
        bat.addTag(TAG);
        // Centre of the block, so a diagonal/vertical rope reads symmetrically.
        bat.snapTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0f, 0f);
        bat.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Attach {@code mob} to {@code holder} and verify it stuck. Vanilla's
     * {@link Leashable#setLeashedTo} is the clean API path (the command path in the spike used the
     * {@code leash} component directly; from Java we go through the interface). Reads back
     * {@link Leashable#getLeashHolder()} up to a few times; a fresh attach can need a tick to
     * resolve, so we retry rather than trust the first call.
     */
    public static boolean attachConfirmed(Mob mob, Entity holder) {
        if (!(mob instanceof Leashable leashable)) {
            return false;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            leashable.setLeashedTo(holder, true);
            if (leashable.isLeashed() && leashable.getLeashHolder() == holder) {
                return true;
            }
        }
        return leashable.isLeashed() && leashable.getLeashHolder() == holder;
    }

    /** Whether an entity is one of our rope endpoints. */
    public static boolean isEndpoint(Entity e) {
        return e instanceof Bat && e.entityTags().contains(TAG);
    }
}
