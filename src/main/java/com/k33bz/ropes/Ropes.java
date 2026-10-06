package com.k33bz.ropes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ropes — craftable ropes strung between fence posts at any angle, drawn by vanilla's own leash
 * renderer so vanilla clients need no mod. Entirely server-side.
 *
 * <p>Mechanism (proven by the spike, MC 26.1.2): a rope cannot be two static knots — a
 * {@code leash_knot} is a leash HOLDER, not leashable. So each segment is a fence knot &rarr; an
 * invisible, pinned, leashable bat, and the leash between them IS the rope. Per-segment span is
 * capped at 11 blocks (vanilla snaps a leash at 12); chain knot-to-knot for longer runs.</p>
 */
public class Ropes implements ModInitializer {
    public static final String MOD_ID = "ropes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Loaded once at init. */
    public static RopesConfig CONFIG;

    /** The append-only climb-session NDJSON writer; null when climb logging is disabled. */
    public static ClimbLogWriter CLIMB_WRITER;

    private int sweepCounter = 0;
    private int climbFlushCounter = 0;
    private int awaitingCounter = 0;

    /** How often (ticks) segments waiting for their chunks' entities are re-checked. */
    private static final int AWAITING_CHECK_TICKS = 20;

    /**
     * Rope-tagged entities that loaded with no stored segment behind them, checked again and
     * removed on the next tick (by then a rope being tied this tick has reached the store).
     */
    private static final List<Stray> STRAYS = new ArrayList<>();

    private record Stray(ServerLevel level, UUID id) {
    }

    @Override
    public void onInitialize() {
        CONFIG = RopesConfig.load();
        RopeStore.store(); // load early so a corrupt store complains at boot
        RopeStore.save();  // ...and materialize it so external tools can rely on the file

        var loaderEarly = net.fabricmc.loader.api.FabricLoader.getInstance();
        if (CONFIG.climbEnabled && CONFIG.climbLog) {
            CLIMB_WRITER = new ClimbLogWriter(resolveClimbLogDir(loaderEarly),
                    java.time.ZoneId.systemDefault());
        } else {
            LOGGER.info("[ropes] climb-session logging disabled (climbEnabled={}, climbLog={})",
                    CONFIG.climbEnabled, CONFIG.climbLog);
        }

        RopeCommands.register();
        registerUse();
        registerCut();
        registerBreak();
        registerVerifyOnLoad();
        registerStrayCleanup();
        registerProtection();
        registerTick();
        registerDisconnect();
        registerClimbLogShutdown();

        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        String version = loader.getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String mc = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        LOGGER.info("[ropes] v{} initialized (server-authoritative) for Minecraft {}", version, mc);
    }

    /** Right-click a fence holding a Rope → arm/complete a segment. */
    private void registerUse() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (!RopeItem.isRope(held)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (!Roping.isFence(serverLevel, pos)) {
                return InteractionResult.PASS;
            }
            Roping.onRightClickFence(serverLevel, sp, pos, true);
            return InteractionResult.SUCCESS; // consume: don't let vanilla lead-tie fire
        });
    }

    /**
     * Shears on a rope → cut it. We accept BOTH the right-click-with-shears path (a shears use on
     * a fence that anchors a rope) and the attack (left-click) path, per the spec.
     */
    private void registerCut() {
        // Right-click with shears on/near a rope fence.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            if (!player.getItemInHand(hand).is(Items.SHEARS)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (Roping.cutNear(serverLevel, pos, 1.5, sp).ok()) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        // Left-click (attack) with shears on a rope fence.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            if (!player.getItemInHand(hand).is(Items.SHEARS)) {
                return InteractionResult.PASS;
            }
            if (Roping.cutNear(serverLevel, pos, 1.5, sp).ok()) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * Breaking either fence post of a segment drops the Rope and cleans up. AFTER fires
     * post-removal (the block is already air), so we match on the STORE, not the block state —
     * {@link Roping#onFenceBroken} cuts every segment anchored at this position.
     */
    private void registerBreak() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel serverLevel) {
                Roping.onFenceBroken(serverLevel, pos);
            }
        });
    }

    /**
     * On server start, queue every stored segment for verification (defensive: the spike showed
     * reload survives, but we re-link a lost endpoint rather than trust it). CHUNK_LOAD queues the
     * segments anchored in a freshly-loaded chunk. Either way the check itself waits until the
     * chunks' entities have loaded (see {@link Roping#drainAwaiting}); before 0.3.1 it ran at once,
     * found no bat yet, and spawned a duplicate on every load of a rope's chunk.
     */
    private void registerVerifyOnLoad() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (RopeStore.segments().isEmpty()) {
                return;
            }
            LOGGER.info("[ropes] verifying {} rope segment(s) as their chunks load", RopeStore.segments().size());
            for (RopeStore.Segment s : RopeStore.segments()) {
                Roping.queueVerify(s);
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) -> {
            String dim = level.dimension().identifier().toString();
            var origin = chunk.getPos();
            for (RopeStore.Segment s : RopeStore.segments()) {
                if (!s.dim.equals(dim)) {
                    continue;
                }
                // Re-verify a segment with either post in this chunk (the knot lives at A, the bat at B)
                if (((s.fenceB[0] >> 4) == origin.x() && (s.fenceB[2] >> 4) == origin.z())
                        || ((s.fenceA[0] >> 4) == origin.x() && (s.fenceA[2] >> 4) == origin.z())) {
                    Roping.queueVerify(s);
                }
            }
        });
    }

    /**
     * Remove rope endpoints and knot caps that load with no stored segment behind them: the
     * duplicates earlier versions spawned (see {@link #registerVerifyOnLoad}), and the leftovers of
     * a rope cut while its far end was unloaded. Stands down for the whole run when the store was
     * unreadable at boot, since every rope entity would look untracked then.
     */
    private void registerStrayCleanup() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (isStray(entity) && !RopeStore.recoveredFromCorruption()) {
                STRAYS.add(new Stray(level, entity.getUUID()));
            }
        });
    }

    private static boolean isStray(Entity entity) {
        if (RopeEndpoint.isEndpoint(entity)) {
            return !RopeStore.hasEndpoint(entity.getUUID().toString());
        }
        if (entity instanceof Display && entity.entityTags().contains(RopeKnots.TAG)) {
            String segId = RopeChecks.segIdOfKnotTags(entity.entityTags());
            return segId == null || !RopeStore.hasSegId(segId);
        }
        return false;
    }

    private static void removeStrays() {
        if (STRAYS.isEmpty()) {
            return;
        }
        List<Stray> batch = new ArrayList<>(STRAYS);
        STRAYS.clear();
        int removed = 0;
        for (Stray stray : batch) {
            Entity e = stray.level().getEntity(stray.id());
            if (e != null && !e.isRemoved() && isStray(e)) { // re-check: it may have been tied since
                Roping.discardQuietly(e);
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("[ropes] removed {} stray rope entit{} with no stored rope", removed, removed == 1 ? "y" : "ies");
        }
    }

    /**
     * Keep players' hands off the entities a rope is made of (0.3.1):
     * <ul>
     *   <li>Endpoint bats take no damage, and can't be interacted with or hit (shears unleash a
     *       leashed mob and drop a lead: a free lead every time the verify sweep re-attached it).</li>
     *   <li>A knot holding a stored rope can't be hit or interacted with either: removing it
     *       dropped the endpoint's leash as a lead, again restored by the sweep for free. Right-
     *       clicking it with a Rope ties from its fence, as clicking the fence itself would.</li>
     *   <li>A Rope can't leash a mob. Lead + String crafts two Ropes, so a Rope that worked as a
     *       lead doubled leads for the price of string.</li>
     * </ul>
     * Cutting a rope is still shears on its fence, and breaking a fence still drops the Rope.
     */
    private void registerProtection() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !RopeEndpoint.isEndpoint(entity));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                !level.isClientSide() && isRopePart(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
                return InteractionResult.PASS;
            }
            boolean holdingRope = RopeItem.isRope(player.getItemInHand(hand));
            if (entity instanceof LeashFenceKnotEntity knot && isRopePart(knot)) {
                if (holdingRope && hand == InteractionHand.MAIN_HAND) {
                    Roping.onRightClickFence(sl, sp, knot.getPos(), true);
                    return InteractionResult.SUCCESS;
                }
                return InteractionResult.FAIL;
            }
            if (RopeEndpoint.isEndpoint(entity)) {
                return InteractionResult.FAIL;
            }
            if (holdingRope) {
                sp.sendOverlayMessage(Component.literal("A Rope ties between fence posts, not to animals."));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    /** An endpoint bat, or a fence knot that a stored rope hangs from. */
    private static boolean isRopePart(Entity entity) {
        if (RopeEndpoint.isEndpoint(entity)) {
            return true;
        }
        if (entity instanceof LeashFenceKnotEntity knot) {
            BlockPos p = knot.getPos();
            return RopeStore.anotherRopeUses(entity.level().dimension().identifier().toString(),
                    p.getX(), p.getY(), p.getZ(), null);
        }
        return false;
    }

    /** Per-tick: rope climbing (every tick) + periodic re-verification sweep. */
    private void registerTick() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Climbing runs every tick (status-effect driven; cheap registry query, no entity scan).
            // This also accumulates + ends climb sessions, enqueueing finished ones on CLIMB_WRITER.
            RopeClimb.tick(server);

            // Stray rope entities seen loading last tick; segments whose chunks' entities arrived
            removeStrays();
            if (++awaitingCounter >= AWAITING_CHECK_TICKS) {
                awaitingCounter = 0;
                Roping.drainAwaiting(server);
            }

            // Drain finished climb sessions to disk off the game thread's buffer, once per interval.
            if (CLIMB_WRITER != null && ++climbFlushCounter >= CONFIG.climbLogFlushIntervalTicks) {
                climbFlushCounter = 0;
                CLIMB_WRITER.drain();
            }

            if (CONFIG.verifyIntervalTicks <= 0) {
                return;
            }
            if (++sweepCounter < CONFIG.verifyIntervalTicks) {
                return;
            }
            sweepCounter = 0;
            for (ServerLevel level : server.getAllLevels()) {
                Roping.verifyAll(level);
            }
        });
    }

    /** Drop a player's half-strung pending anchor when they disconnect. */
    private void registerDisconnect() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                Roping.clearPending(handler.player.getUUID()));
    }

    /** On clean shutdown, drain + flush + close the climb writer so nothing buffered is lost. */
    private void registerClimbLogShutdown() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (CLIMB_WRITER != null) {
                CLIMB_WRITER.shutdown();
            }
        });
    }

    /** Resolve the configured climb-log dir relative to the run/game dir (or honor an absolute path). */
    private static java.nio.file.Path resolveClimbLogDir(net.fabricmc.loader.api.FabricLoader loader) {
        java.nio.file.Path p = java.nio.file.Paths.get(CONFIG.climbLogDir);
        return p.isAbsolute() ? p : loader.getGameDir().resolve(CONFIG.climbLogDir);
    }
}
