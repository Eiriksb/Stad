package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Right-clicking a citizen: who they are and how they're doing, with buttons for the orders the player may give. */
final class CitizenInteraction {
    private CitizenInteraction() {}

    static void show(ServerPlayer player, CitizenEntity citizen) {
        ColonyManager colonies = ColonyManager.get(player.server);
        Optional<Colony> home = citizen.colony();
        MutableComponent card = Component.literal(citizen.getName().getString()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (home.isPresent()) {
            card.append(Component.literal("  of " + home.get().name()).withStyle(ChatFormatting.GRAY));
            String job = citizen.job().orElse(null);
            card.append(Component.literal(String.format("\n%s · Food %d/20 · Rest %d/20 · Happiness %d%% · %s", job == null ? "No job" : capitalize(job),
                    Math.round(citizen.hunger()), Math.round(citizen.rest()), citizen.happiness(), citizen.home().isPresent() ? "has a bed" : "homeless"))
                    .withStyle(ChatFormatting.GRAY));
            if (home.get().rank(player.getUUID()).atLeast(OrderKind.ASSIGN_JOB.minRank)) {
                card.append("\n");
                if (job == null) {
                    card.append(button("Make builder", "/stad citizen job " + citizen.getUUID() + " builder", ChatFormatting.AQUA));
                } else {
                    card.append(button("Stop working", "/stad citizen job " + citizen.getUUID() + " none", ChatFormatting.YELLOW));
                }
                card.append(" ");
            }
            if (home.get().rank(player.getUUID()).atLeast(OrderKind.DISMISS.minRank)) {
                card.append(button("Ask to leave town", "/stad citizen dismiss " + citizen.getUUID(), ChatFormatting.RED));
            }
        } else {
            card.append(Component.literal("  newcomer, looking for a town").withStyle(ChatFormatting.GRAY));
            Optional<Colony> here = colonies.at(citizen.level().dimension(), citizen.blockPosition());
            if (here.isPresent() && here.get().rank(player.getUUID()).atLeast(OrderKind.INVITE.minRank)) {
                card.append("\n").append(button("Invite to " + here.get().name(), "/stad citizen invite " + citizen.getUUID(), ChatFormatting.GREEN));
            } else if (here.isEmpty() && !colonies.ownedBy(player.getUUID()).isEmpty()) {
                card.append(Component.literal("\nBring them onto your town's land to invite them.").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        card.append(Component.literal("\nTalk to them by looking at them and typing in chat.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        player.sendSystemMessage(card);
    }

    private static Component button(String label, String command, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(label))));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static boolean canManage(Rank r) {
        return r.atLeast(Rank.OFFICER);
    }
}
