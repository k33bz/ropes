package com.k33bz.ropes;

import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * Decorative junction caps. At every tie-point of a segment we spawn a small {@code item_display}
 * so the connection reads as a deliberate <b>tied knot</b>, not a rope vanishing into a fence.
 * Three caps per segment:
 * <ol>
 *   <li>at fence A's leash-knot position (the holder end),</li>
 *   <li>at fence B's position (the endpoint-mob end),</li>
 *   <li>at the invisible endpoint bat itself (the moving end — same block as fence B here, but
 *       kept as its own cap so it tracks the mob's tie-point).</li>
 * </ol>
 *
 * <p>Each cap is tagged {@code ropes_knot} + {@code ropes_knot_<segId>} so cut / fence-break /
 * reload cleanup removes them together with the segment — the same tag-scoped cleanup postbox
 * uses for its mailbox displays. The invisible bat still renders the actual rope; these are
 * purely decorative. Toggle with {@link RopesConfig#showKnots}.</p>
 *
 * <p>Default look: a small {@code minecraft:lead} item (a coil of rope — reads as a knot with no
 * external texture, guaranteed pure-vanilla-valid). If {@link RopesConfig#knotHeadTexture} is set
 * to a player-head texture value, we render a scaled player_head with that skin instead.</p>
 */
public final class RopeKnots {
    private RopeKnots() {
    }

    /** Cluster tag on every knot cap of every segment. */
    public static final String TAG = "ropes_knot";

    private static String perSegTag(String segId) {
        return TAG + "_" + segId;
    }

    /** A stable per-segment id from the endpoint UUID (segments are keyed by their endpoint). */
    public static String segId(RopeStore.Segment seg) {
        return seg.endpointUuid == null ? "unknown" : seg.endpointUuid.replace("-", "");
    }

    /** Spawn the three knot caps for a segment (idempotent: kills this segment's old caps first). */
    public static void spawn(ServerLevel level, RopeStore.Segment seg) {
        if (!Ropes.CONFIG.showKnots) {
            return;
        }
        String seg1 = perSegTag(segId(seg));
        kill(level, seg); // idempotent
        // Fence A knot (holder end): render near the leash attach height on the post.
        cap(level, seg1, seg.fenceA[0] + 0.5, seg.fenceA[1] + 0.75, seg.fenceA[2] + 0.5);
        // Fence B / endpoint (moving end): the bat sits at block-centre (y+0.5).
        cap(level, seg1, seg.fenceB[0] + 0.5, seg.fenceB[1] + 0.5, seg.fenceB[2] + 0.5);
        // A third cap right at the endpoint tie-point so the moving junction reads tied too.
        cap(level, seg1, seg.fenceB[0] + 0.5, seg.fenceB[1] + 0.75, seg.fenceB[2] + 0.5);
    }

    private static void cap(ServerLevel level, String segTag, double x, double y, double z) {
        String tex = Ropes.CONFIG.knotHeadTexture;
        String item;
        if (tex != null && !tex.isBlank()) {
            item = "item:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":"
                    + "{properties:[{name:\"textures\",value:\"" + tex + "\"}]}}},";
        } else {
            // A small coil of lead reads as a tied knot with zero external dependencies.
            item = "item:{id:\"minecraft:lead\",count:1},";
        }
        float s = (float) Ropes.CONFIG.knotScale;
        run(level, String.format(Locale.ROOT,
                "summon minecraft:item_display %.3f %.3f %.3f {Tags:[\"%s\",\"%s\"],"
                        + "%s"
                        + "billboard:\"center\","
                        + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[0f,0f,0f],scale:[%sf,%sf,%sf]},"
                        + "brightness:{sky:15,block:15}}",
                x, y, z, TAG, segTag, item, s, s, s));
    }

    /** Kill this segment's knot caps (tag-scoped). */
    public static void kill(ServerLevel level, RopeStore.Segment seg) {
        run(level, "kill @e[tag=" + perSegTag(segId(seg)) + "]");
    }

    /** Run a command from the server console source (postbox's Mail.run pattern). */
    static void run(ServerLevel level, String command) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
