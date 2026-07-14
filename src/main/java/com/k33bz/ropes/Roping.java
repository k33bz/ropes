package com.k33bz.ropes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The rope engine — shared by BOTH the right-click interaction path and the {@code /rope} command
 * path (this session's lesson: sneak/right-click can't be driven by the harness, so every
 * interaction has a command twin that runs the exact same logic). Nothing here talks to the player
 * via a screen; it takes coordinates and a player-or-null and does the work.
 *
 * <p>A segment is <b>fence A (knot / leash holder) &rarr; invisible endpoint bat at fence B</b>.
 * To chain past the 11-block per-segment ceiling, string A&rarr;B, then start the next segment
 * from B: B becomes the next segment's fence A (its own knot), so ropes daisy-chain knot-to-knot
 * across arbitrary distance.</p>
 */
public final class Roping {
    private Roping() {
    }

    /** Per-player pending anchor: the first fence they clicked, waiting for a second. */
    private static final Map<UUID, BlockPos> PENDING = new HashMap<>();

    // ------------------------------------------------------------------ helpers

    public static boolean isFence(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.FENCES);
    }

    private static void feedback(ServerPlayer player, String msg) {
        if (player != null) {
            player.sendOverlayMessage(Component.literal(msg));
        }
    }

    private static int[] xyz(BlockPos p) {
        return new int[] {p.getX(), p.getY(), p.getZ()};
    }

    // ------------------------------------------------------------------ interaction path

    /**
     * Right-click-with-a-Rope on a fence. First click on a fence arms a pending anchor; the second
     * fence within range strings the segment (and consumes one Rope). Clicking the same fence
     * twice, or an out-of-range second fence, is rejected with an actionbar message.
     *
     * @return true if the click was consumed (a rope was started or completed or rejected here).
     */
    public static boolean onRightClickFence(ServerLevel level, ServerPlayer player, BlockPos fence,
                                             boolean consumeItem) {
        UUID id = player.getUUID();
        BlockPos anchor = PENDING.get(id);
        if (anchor == null) {
            PENDING.put(id, fence.immutable());
            feedback(player, "Rope anchored — right-click a second fence within "
                    + Ropes.CONFIG.maxSpanBlocks + " blocks.");
            return true;
        }
        PENDING.remove(id);
        if (anchor.equals(fence)) {
            feedback(player, "Pick a different fence for the other end.");
            return true;
        }
        Result r = tie(level, anchor, fence, player);
        if (r.ok && consumeItem) {
            consumeOneRope(player);
        }
        return true;
    }

    /** Discard a player's pending anchor (e.g. on disconnect). */
    public static void clearPending(UUID id) {
        PENDING.remove(id);
    }

    public static boolean hasPending(UUID id) {
        return PENDING.containsKey(id);
    }

    private static void consumeOneRope(ServerPlayer player) {
        var main = player.getMainHandItem();
        if (RopeItem.isRope(main) && !player.isCreative()) {
            main.shrink(1);
        }
    }

    // ------------------------------------------------------------------ core tie / cut

    public record Result(boolean ok, String message) {
    }

    /**
     * Create one rope segment between two fences: place (or reuse) a knot at {@code fenceA}, spawn
     * the invisible endpoint bat at {@code fenceB}, and leash it to A's knot (retry-until-read-back
     * as the spike demands). Persists the segment on success (store saves on every mutation).
     * Validates both blocks are fences and the span is within {@link RopesConfig#maxSpanBlocks}.
     */
    /** True if the stored {@code [x,y,z]} equals the given block position. */
    private static boolean samePos(int[] p, BlockPos b) {
        return p != null && p.length == 3 && p[0] == b.getX() && p[1] == b.getY() && p[2] == b.getZ();
    }

    public static Result tie(ServerLevel level, BlockPos fenceA, BlockPos fenceB, ServerPlayer player) {
        if (!isFence(level, fenceA) || !isFence(level, fenceB)) {
            String m = "Both ends must be fence posts.";
            feedback(player, m);
            return new Result(false, m);
        }
        double span = RopeMath.span(fenceA.getX(), fenceA.getY(), fenceA.getZ(),
                fenceB.getX(), fenceB.getY(), fenceB.getZ());
        if (!RopeMath.withinSpan(span, Ropes.CONFIG.maxSpanBlocks)) {
            String m = String.format(Locale.ROOT,
                    "Too far — %.1f blocks (max %d). Chain from a knot for longer runs.",
                    span, Ropes.CONFIG.maxSpanBlocks);
            feedback(player, m);
            return new Result(false, m);
        }
        // Refuse duplicate ties (same two posts, either orientation) and enforce the global segment
        // cap BEFORE spawning any entity — bounds the permission-0 /rope tie resource-exhaustion.
        String dimStr = level.dimension().identifier().toString();
        for (RopeStore.Segment s : RopeStore.segments()) {
            if (s.dim.equals(dimStr)
                    && ((samePos(s.fenceA, fenceA) && samePos(s.fenceB, fenceB))
                            || (samePos(s.fenceA, fenceB) && samePos(s.fenceB, fenceA)))) {
                String m = "There's already a rope between those posts.";
                feedback(player, m);
                return new Result(false, m);
            }
        }
        if (RopeStore.segments().size() >= Ropes.CONFIG.maxSegments) {
            String m = String.format(Locale.ROOT,
                    "The server rope limit (%d) is reached.", Ropes.CONFIG.maxSegments);
            feedback(player, m);
            return new Result(false, m);
        }
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level, fenceA);
        UUID endpoint = RopeEndpoint.spawnLeashed(level, fenceB, knot);
        if (endpoint == null) {
            // Attach couldn't be confirmed. If this knot is now unused, cull it so we don't orphan.
            cullKnotIfUnused(level, fenceA, null);
            String m = "Could not attach the rope — try again.";
            feedback(player, m);
            return new Result(false, m);
        }
        RopeStore.Segment seg = new RopeStore.Segment(
                level.dimension().identifier().toString(),
                xyz(fenceA), xyz(fenceB), endpoint.toString(),
                player != null ? player.getUUID().toString() : null);
        RopeStore.add(seg);
        RopeKnots.spawn(level, seg); // decorative junction caps (tag-scoped to this segment)
        String m = String.format(Locale.ROOT, "Rope strung (%.1f blocks).", span);
        feedback(player, m);
        Ropes.LOGGER.info("[ropes] tied {} <-> {} in {} (endpoint {})",
                fenceA.toShortString(), fenceB.toShortString(), seg.dim, endpoint);
        return new Result(true, m);
    }

    /**
     * Cut the rope segment whose endpoint is nearest {@code near} (within {@code radius} blocks):
     * discard the endpoint bat, remove the segment, cull A's knot if no other rope uses it, and
     * drop a Rope back (unless config says otherwise). Store saves on the mutation.
     */
    public static Result cutNear(ServerLevel level, BlockPos near, double radius, ServerPlayer player) {
        String dim = level.dimension().identifier().toString();
        RopeStore.Segment best = null;
        double bestSq = radius * radius;
        for (RopeStore.Segment s : RopeStore.segments()) {
            if (!s.dim.equals(dim)) {
                continue;
            }
            for (int[] f : new int[][] {s.fenceA, s.fenceB}) {
                double dsq = near.distSqr(new BlockPos(f[0], f[1], f[2]));
                if (dsq <= bestSq) {
                    bestSq = dsq;
                    best = s;
                }
            }
        }
        if (best == null) {
            String m = "No rope here to cut.";
            feedback(player, m);
            return new Result(false, m);
        }
        // Only the owner (or a creative-mode admin / non-player command source) may cut a rope —
        // stops a player severing someone else's ropes at range via /rope cut. isCreative() is the
        // same privilege proxy the mod already uses for anchor renaming (AnchorDialog.canRename).
        if (player != null && best.owner != null
                && !best.owner.equals(player.getUUID().toString())
                && !player.isCreative()) {
            String m = "That rope isn't yours to cut.";
            feedback(player, m);
            return new Result(false, m);
        }
        cut(level, best);
        String m = "Rope cut.";
        feedback(player, m);
        return new Result(true, m);
    }

    /** Cut a specific segment: discard endpoint, drop rope, cull orphan knot, remove record. */
    public static void cut(ServerLevel level, RopeStore.Segment seg) {
        RopeKnots.kill(level, seg); // remove decorative caps first (keyed off the endpoint UUID)
        discardEndpoint(level, seg);
        BlockPos fenceA = new BlockPos(seg.fenceA[0], seg.fenceA[1], seg.fenceA[2]);
        RopeStore.remove(seg);
        cullKnotIfUnused(level, fenceA, seg);
        if (Ropes.CONFIG.dropRopeOnBreak) {
            BlockPos drop = new BlockPos(seg.fenceB[0], seg.fenceB[1], seg.fenceB[2]);
            Block.popResource(level, drop, RopeItem.create());
        }
        Ropes.LOGGER.info("[ropes] cut segment {} <-> {} (endpoint {})",
                fenceA.toShortString(),
                new BlockPos(seg.fenceB[0], seg.fenceB[1], seg.fenceB[2]).toShortString(),
                seg.endpointUuid);
    }

    /** Discard the endpoint bat if it is still present (removes its leash automatically). */
    static void discardEndpoint(ServerLevel level, RopeStore.Segment seg) {
        try {
            Entity e = level.getEntity(UUID.fromString(seg.endpointUuid));
            if (e != null) {
                if (e instanceof Leashable l && l.isLeashed()) {
                    l.removeLeash();
                }
                e.discard();
            }
        } catch (IllegalArgumentException ignored) {
            // bad UUID string — nothing to discard
        }
    }

    /**
     * Remove the leash_knot at {@code fenceA} if no remaining segment anchors there. A bare knot
     * with no leash doesn't persist across reload anyway (the spike showed vanilla culls it), but
     * we remove it eagerly so a live world doesn't show a dangling knot.
     */
    static void cullKnotIfUnused(ServerLevel level, BlockPos fenceA, RopeStore.Segment except) {
        String dim = level.dimension().identifier().toString();
        if (RopeStore.anotherRopeUses(dim, fenceA.getX(), fenceA.getY(), fenceA.getZ(), except)) {
            return;
        }
        for (LeashFenceKnotEntity knot : level.getEntitiesOfClass(
                LeashFenceKnotEntity.class, new net.minecraft.world.phys.AABB(fenceA).inflate(0.5))) {
            if (knot.getPos().equals(fenceA)) {
                knot.discard();
            }
        }
    }

    // ------------------------------------------------------------------ break / verify

    /**
     * A fence at {@code pos} was broken. Cut every segment that had an endpoint there — dropping a
     * Rope and cleaning up the endpoint bat + any orphaned knot on the OTHER end.
     */
    public static void onFenceBroken(ServerLevel level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        for (RopeStore.Segment s : RopeStore.touching(dim, pos.getX(), pos.getY(), pos.getZ())) {
            cut(level, s);
        }
    }

    /**
     * Re-verify one segment's leash still resolves; re-link (or re-spawn) a lost endpoint. The
     * spike showed reload survives, but we're defensive: if the endpoint entity is gone, we
     * re-spawn it; if it exists but isn't leashed, we re-attach it. Returns true if the segment is
     * (now) healthy, false if it had to be dropped as unrecoverable.
     */
    public static boolean verifySegment(ServerLevel level, RopeStore.Segment seg) {
        BlockPos fenceA = new BlockPos(seg.fenceA[0], seg.fenceA[1], seg.fenceA[2]);
        BlockPos fenceB = new BlockPos(seg.fenceB[0], seg.fenceB[1], seg.fenceB[2]);
        // If either fence is gone, the segment is dead — clean it up like a break.
        if (!isFence(level, fenceA) || !isFence(level, fenceB)) {
            cut(level, seg);
            return false;
        }
        Entity e = null;
        try {
            e = level.getEntity(UUID.fromString(seg.endpointUuid));
        } catch (IllegalArgumentException ignored) {
            // fall through: re-spawn
        }
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level, fenceA);
        if (e instanceof net.minecraft.world.entity.ambient.Bat bat && RopeEndpoint.isEndpoint(bat)) {
            if (bat instanceof Leashable l && l.isLeashed() && l.getLeashHolder() == knot) {
                return true; // healthy
            }
            // exists but detached — re-attach in place
            if (RopeEndpoint.attachConfirmed(bat, knot)) {
                Ropes.LOGGER.info("[ropes] re-linked detached endpoint {}", seg.endpointUuid);
                return true;
            }
            bat.discard();
        }
        // endpoint lost entirely — re-spawn a fresh one and re-point the record.
        // The knot caps are keyed on the (old) endpoint UUID, so kill them BEFORE we change it,
        // then re-spawn caps under the new id — no orphaned displays leak.
        RopeKnots.kill(level, seg);
        UUID fresh = RopeEndpoint.spawnLeashed(level, fenceB, knot);
        if (fresh == null) {
            Ropes.LOGGER.warn("[ropes] could not restore endpoint for segment at {}; dropping",
                    fenceA.toShortString());
            cut(level, seg);
            return false;
        }
        seg.endpointUuid = fresh.toString();
        RopeStore.save(); // mutation → immediate save
        RopeKnots.spawn(level, seg); // re-cap under the new endpoint id
        Ropes.LOGGER.info("[ropes] re-spawned lost endpoint as {} for segment at {}",
                fresh, fenceA.toShortString());
        return true;
    }

    /** Re-verify every stored segment (boot + periodic). Snapshot to avoid concurrent-mod. */
    public static void verifyAll(ServerLevel level) {
        String dim = level.dimension().identifier().toString();
        for (RopeStore.Segment s : new java.util.ArrayList<>(RopeStore.segments())) {
            if (s.dim.equals(dim)) {
                verifySegment(level, s);
            }
        }
    }
}
