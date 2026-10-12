package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.StadRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Only a town's founder may take its town hall down. (Protecting the rest of the land comes with raids, M8.) */
public final class ColonyProtection {
    private ColonyProtection() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ColonyProtection::onBreak);
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!event.getState().is(StadRegistries.TOWN_HALL.get()) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ColonyManager.get(level.getServer()).at(level.dimension(), event.getPos()).filter(c -> c.center.equals(event.getPos()))
                .ifPresent(c -> {
                    if (!c.owner().equals(event.getPlayer().getUUID())) {
                        event.setCanceled(true);
                        event.getPlayer().displayClientMessage(Component.literal("Only " + c.ownerName() + " can take down the town hall of "
                                + c.name() + ".").withStyle(ChatFormatting.RED), true);
                    }
                });
    }
}
