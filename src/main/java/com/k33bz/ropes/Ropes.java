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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * On server start, re-verify every stored segment's leash (defensive — the spike showed reload
     * survives, but we re-link a lost endpoint rather than trust it). CHUNK_LOAD re-checks the
     * segments anchored in a freshly-loaded chunk, so a rope in an unloaded region is restored the
     * moment its chunk comes back.
     */
    private void registerVerifyOnLoad() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (RopeStore.segments().isEmpty()) {
                return;
            }
            LOGGER.info("[ropes] verifying {} rope segment(s) at startup", RopeStore.segments().size());
            for (ServerLevel level : server.getAllLevels()) {
                Roping.verifyAll(level);
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) -> {
            String dim = level.dimension().identifier().toString();
            var origin = chunk.getPos();
            for (RopeStore.Segment s : new java.util.ArrayList<>(RopeStore.segments())) {
                if (!s.dim.equals(dim)) {
                    continue;
                }
                // Re-verify a segment whose endpoint fence sits in this chunk.
                if ((s.fenceB[0] >> 4) == origin.x() && (s.fenceB[2] >> 4) == origin.z()) {
                    Roping.verifySegment(level, s);
                }
            }
        });
    }

    /** Per-tick: rope climbing (every tick) + periodic re-verification sweep. */
    private void registerTick() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Climbing runs every tick (status-effect driven; cheap registry query, no entity scan).
            // This also accumulates + ends climb sessions, enqueueing finished ones on CLIMB_WRITER.
            RopeClimb.tick(server);

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
