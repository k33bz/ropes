package com.k33bz.ropes;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * The {@code /rope} command surface — the command TWIN of the right-click interaction (this
 * session's hard lesson: sneak/right-click can't be driven through the harness/ViaProxy, and a
 * command backend is also an accessibility win). Every command runs the SAME {@link Roping} logic
 * with the SAME rules (span validation, knot chaining, endpoint spawn + retry-confirm, store save
 * on every mutation) as the interaction path.
 *
 * <ul>
 *   <li>{@code /rope tie <ax ay az> <bx by bz>} — string a segment A&rarr;B (validates span).</li>
 *   <li>{@code /rope cut <x y z>} — cut the rope nearest that block (your own ropes only).</li>
 *   <li>{@code /rope give [count]} — give the caller Rope items (ops only, permission 2).</li>
 *   <li>{@code /rope list} — how many segments are stored (permission 0, read-only).</li>
 * </ul>
 *
 * <p>Permission: {@code tie}/{@code cut} stay permission 0 (accessibility), and since 0.3.1 they
 * grant nothing a player couldn't do by hand: a survival player's {@code /rope tie} needs both
 * posts within {@link RopesConfig#tieReachBlocks} and pays one Rope from their inventory, like the
 * right-click path. Before, it was free and worked at any distance, so anyone could string ropes
 * through someone else's base and fill the server-wide segment cap. Ops, creative players and the
 * console are exempt. {@code /rope give} handed out free Ropes (each a working lead) to anyone and
 * is now op-only.</p>
 */
public final class RopeCommands {
    private RopeCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("rope")
                        .then(Commands.literal("tie")
                                .then(Commands.argument("fenceA", BlockPosArgument.blockPos())
                                        .then(Commands.argument("fenceB", BlockPosArgument.blockPos())
                                                .executes(RopeCommands::tie))))
                        .then(Commands.literal("cut")
                                .then(Commands.argument("near", BlockPosArgument.blockPos())
                                        .executes(RopeCommands::cut)))
                        .then(Commands.literal("give")
                                .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> give(ctx, 1))
                                .then(Commands.argument("count",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> give(ctx,
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(ctx, "count")))))
                        .then(Commands.literal("list")
                                .executes(RopeCommands::list))));
    }

    private static int tie(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No world."));
            return 0;
        }
        BlockPos a = BlockPosArgument.getLoadedBlockPos(ctx, "fenceA");
        BlockPos b = BlockPosArgument.getLoadedBlockPos(ctx, "fenceB");
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        // Survival players play by the right-click rules: both posts in reach, one Rope paid
        boolean exempt = player == null || player.isCreative()
                || Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(src);
        if (!exempt) {
            double reach = Ropes.CONFIG.tieReachBlocks;
            Vec3 eye = player.getEyePosition();
            if (!RopeChecks.withinReach(eye.x, eye.y, eye.z, a.getX(), a.getY(), a.getZ(), reach)
                    || !RopeChecks.withinReach(eye.x, eye.y, eye.z, b.getX(), b.getY(), b.getZ(), reach)) {
                src.sendFailure(Component.literal(String.format(Locale.ROOT,
                        "Both posts must be within %.0f blocks of you.", reach)));
                return 0;
            }
            if (!Roping.hasRope(player)) {
                src.sendFailure(Component.literal("You need a Rope to tie with."));
                return 0;
            }
        }
        Roping.Result r = Roping.tie(level, a, b, player);
        if (r.ok() && !exempt) {
            Roping.takeOneRope(player);
        }
        if (r.ok()) {
            src.sendSuccess(() -> Component.literal(r.message()).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        src.sendFailure(Component.literal(r.message()));
        return 0;
    }

    private static int cut(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No world."));
            return 0;
        }
        BlockPos near = BlockPosArgument.getSpawnablePos(ctx, "near");
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        Roping.Result r = Roping.cutNear(level, near, 2.0, player);
        if (r.ok()) {
            src.sendSuccess(() -> Component.literal(r.message()).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        src.sendFailure(Component.literal(r.message()));
        return 0;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Players only."));
            return 0;
        }
        // add + drop the rest: placeItemBackInInventory gained a Prediction argument in 26.3,
        // and these two read the same on every Minecraft line
        ItemStack ropes = RopeItem.create(count);
        if (!player.getInventory().add(ropes) && !ropes.isEmpty() && player.level() instanceof ServerLevel sl) {
            player.spawnAtLocation(sl, ropes);
        }
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Gave %d Rope.", count))
                .withStyle(ChatFormatting.GOLD), false);
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        int n = RopeStore.segments().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format(Locale.ROOT, "%d rope segment(s) stored.", n))
                .withStyle(ChatFormatting.GOLD), false);
        return n;
    }
}
