package com.k33bz.ropes;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The <b>Rope</b> — a pure-vanilla item so vanilla clients can hold, craft, and see it with no
 * mod. It is a {@link Items#LEAD LEAD} carrying a marker in its {@code custom_data} component
 * ({@code {ropes_item:1b}}) plus a "Rope" custom name — the same identify-by-component trick as
 * postbox's Rainbow Mailbox head. A lead reads best as a rope (it already looks like coiled cord)
 * and a plain lead's own leash behaviour never fires from a fence right-click, so there's no
 * collision with our interaction.
 *
 * <p>The datapack recipe {@code data/ropes/recipe/rope.json} (LEAD + STRING &rarr; 2 marked
 * leads) is what lets vanilla clients craft it; this class builds the same stack for commands
 * and drops, and matches it on use.</p>
 */
public final class RopeItem {
    private RopeItem() {
    }

    /** Marker key inside the {@code custom_data} component. Anvil renames can't forge this. */
    public static final String MARKER_KEY = "ropes_item";

    /** Build one Rope item (a marked lead). */
    public static ItemStack create() {
        return create(1);
    }

    /** Build a Rope stack of the given count. */
    public static ItemStack create(int count) {
        ItemStack stack = new ItemStack(Items.LEAD, count);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARKER_KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Rope")
                .withStyle(style -> style.withColor(ChatFormatting.WHITE).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click two fence posts to string a rope between them.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("Max 11 blocks per segment — chain from a knot for longer runs.")
                        .withStyle(ChatFormatting.DARK_GRAY))));
        return stack;
    }

    /** Whether this stack is a Rope (a lead carrying our custom_data marker). */
    public static boolean isRope(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.LEAD)) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(MARKER_KEY, false);
    }
}
