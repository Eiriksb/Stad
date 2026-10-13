package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Right-clicking a citizen: the chat screen ({@link CitizenScreens}), or for players without Stad on their client, a card in
 * chat with who they are, how they're doing and buttons for the orders the player may give.
 */
final class CitizenInteraction {
    private CitizenInteraction() {}

    static void show(ServerPlayer player, CitizenEntity citizen) {
        if (CitizenScreens.canShow(player)) {
            CitizenScreens.open(player, citizen);
            return;
        }
        ColonyManager colonies = ColonyManager.get(player.server);
        Optional<Colony> home = citizen.colony();
        MutableComponent card = Component.literal(citizen.getName().getString()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (home.isPresent()) {
            card.append(Component.literal("  of " + home.get().name()).withStyle(ChatFormatting.GRAY));
            String job = citizen.isBaby() ? "child" : citizen.job().orElse(null);
            card.append(Component.literal(String.format("\n%s · Food %d/20 · Rest %d/20 · Happiness %d%% · %s", job == null ? "No job" : capitalize(job),
                    Math.round(citizen.hunger()), Math.round(citizen.rest()), citizen.happiness(), citizen.home().isPresent() ? "has a bed" : "homeless"))
                    .withStyle(ChatFormatting.GRAY));
            citizen.record().filter(r -> r.condition != null).ifPresent(r -> card.append(Component.literal("\nWaiting until "
                    + dev.eiriksb.stad.agent.Leadership.conditionText(r.condition) + " to be the " + r.pendingJob).withStyle(ChatFormatting.YELLOW)));
            if (citizen.grudging()) {
                card.append(Component.literal("\nWorking grudgingly today").withStyle(ChatFormatting.YELLOW));
            }
            if (!citizen.isBaby() && home.get().rank(player.getUUID()).atLeast(OrderKind.ASSIGN_JOB.minRank)) {
                card.append("\n");
                if (job == null) {
                    for (String j : Jobs.all()) {
                        card.append(button(capitalize(j), "/stad citizen job " + citizen.getUUID() + " " + j, ChatFormatting.AQUA)).append(" ");
                    }
                } else {
                    card.append(button("Stop working", "/stad citizen job " + citizen.getUUID() + " none", ChatFormatting.YELLOW));
                }
                card.append(" ");
            }
            if (home.get().rank(player.getUUID()).atLeast(OrderKind.DISMISS.minRank)) {
                card.append(button("Ask to leave town", "/stad citizen dismiss " + citizen.getUUID(), ChatFormatting.RED));
            }
            Optional<Colony> mine = colonies.all().stream().filter(c -> c.rank(player.getUUID()).atLeast(OrderKind.INVITE.minRank)).findFirst();
            if (!citizen.isBaby() && !home.get().rank(player.getUUID()).atLeast(Rank.MEMBER) && mine.isPresent() && home.get().politics.poaching) {
                card.append("\n").append(button("Invite to " + mine.get().name(), "/stad citizen invite " + citizen.getUUID(), ChatFormatting.GOLD));
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
        citizen.record().filter(r -> !r.plan.isEmpty()).ifPresent(r -> card.append(Component.literal("\nToday: " + String.join("; ", r.plan))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
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
