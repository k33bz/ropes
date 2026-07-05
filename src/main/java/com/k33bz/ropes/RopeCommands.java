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
 *   <li>{@code /rope cut <x y z>} — cut the rope nearest that block.</li>
 *   <li>{@code /rope give [count]} — give the caller Rope items (permission 0, self-serve).</li>
 *   <li>{@code /rope list} — how many segments are stored (permission 0, read-only).</li>
 * </ul>
 *
 * <p>Permission: {@code tie}/{@code cut} are permission 0 per spec (accessibility) — the same
 * effect a player achieves with a Rope by hand, so no new power is granted. Servers wanting to
 * gate rope-building should restrict the command via their permission mod.</p>
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
        Roping.Result r = Roping.tie(level, a, b, player);
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
        player.getInventory().placeItemBackInInventory(RopeItem.create(count));
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
