package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Handing a citizen something by right-clicking them with it (their town's members): food, a tool or weapon, armour,
 * seeds, saplings or coal. Armour goes on where they're wearing none and a tool for their job into their hand if
 * they're without one; the rest goes in their own things. Anything else, and sneaking, still opens their screens.
 * Server thread.
 */
public final class Giving {
    private Giving() {}

    /** Whether right-clicking with it hands it over. */
    public static boolean giveable(ItemStack s) {
        return !s.isEmpty() && (Homes.edible(s) || Tools.kindOf(s) != null || s.getItem() instanceof net.minecraft.world.item.SwordItem
                || armourSlot(s) != null || s.is(ItemTags.SAPLINGS) || s.is(net.minecraft.tags.ItemTags.VILLAGER_PLANTABLE_SEEDS)
                || s.is(net.minecraft.world.item.Items.WHEAT_SEEDS) || Smithing.fuel(s));
    }

    private static EquipmentSlot armourSlot(ItemStack s) {
        for (EquipmentSlot slot : dev.eiriksb.stad.war.Combat.ARMOUR) {
            if (CitizenMenu.fits(s, slot)) {
                return slot;
            }
        }
        return null;
    }

    /** Whether this player may hand them things: a member of their town. */
    public static boolean allowed(ServerPlayer player, CitizenEntity c) {
        return c.colony().map(t -> t.rank(player.getUUID()).atLeast(Rank.MEMBER)).orElse(false);
    }

    /** Hands over what the player holds; returns whether anything was taken. */
    public static boolean give(ServerPlayer player, CitizenEntity c, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        ItemStack gift = player.getAbilities().instabuild ? held.copy() : held;
        String what = held.getHoverName().getString().toLowerCase(Locale.ROOT);
        String first = c.getName().getString().split(" ")[0];
        int before = gift.getCount();
        String said = null;
        EquipmentSlot slot = armourSlot(gift);
        Tools.Kind tool = Tools.kindOf(gift);
        if (slot != null && c.getItemBySlot(slot).isEmpty()) {
            c.setItemSlot(slot, gift.split(1));
            said = first + " puts on the " + what;
        } else if (tool != null && tool == Tools.of(c.job().orElse(null)) && tool != Tools.Kind.SWORD && Tools.lacking(c)) {
            ItemStack old = c.getItemBySlot(EquipmentSlot.MAINHAND);
            c.setItemSlot(EquipmentSlot.MAINHAND, gift.split(1));
            if (!old.isEmpty()) {
                c.spawnAtLocation(c.belongings().addItem(old));
            }
            said = first + " takes the " + what + " to work with";
        } else {
            ItemStack rest = c.belongings().addItem(gift.copy());
            int taken = gift.getCount() - rest.getCount();
            gift.shrink(taken);
            if (taken > 0) {
                said = first + " takes " + (taken == 1 ? "the " + what : taken + " " + what);
            }
        }
        if (said == null) {
            player.displayClientMessage(Component.literal(first + " has no room for it: see to their gear (sneak and right-click)")
                    .withStyle(ChatFormatting.YELLOW), true);
            return false;
        }
        if (gift.getCount() != before || player.getAbilities().instabuild) {
            c.swing(InteractionHand.MAIN_HAND);
            c.playSound(SoundEvents.ITEM_PICKUP, 0.5f, 1.2f);
            player.displayClientMessage(Component.literal(said).withStyle(ChatFormatting.GREEN), true);
        }
        return true;
    }
}
