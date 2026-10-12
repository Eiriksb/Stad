package dev.eiriksb.stad.net;

import dev.eiriksb.stad.block.TownHallBlock;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Optional;

/** Stad's network messages. */
public final class StadNetwork {
    private StadNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(StadNetwork::payloads);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(ColonyInfoPayload.TYPE, ColonyInfoPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.openTownHall(p.view());
            }
        });
        r.playToServer(ColonyActionPayload.TYPE, ColonyActionPayload.CODEC, StadNetwork::action);
    }

    private static void action(ColonyActionPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        Optional<Colony> found = ColonyManager.get(player.server).byId(p.colony());
        if (found.isEmpty()) {
            return;
        }
        Colony colony = found.get();
        ServerLevel level = player.server.getLevel(colony.dimension);
        if (level == null || player.level() != level || player.distanceToSqr(colony.center.getCenter()) > 64) {
            return; // the screen belongs to a town hall the player is standing at
        }
        switch (p.action()) {
            case RENAME -> {
                String name = p.text().strip();
                if (!colony.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
                    player.displayClientMessage(Component.literal("Only officers can rename the town.").withStyle(ChatFormatting.RED), true);
                } else if (name.length() >= 2 && name.length() <= 32) {
                    colony.rename(name);
                    ColonyView.send(player, colony, level);
                }
            }
            case OPEN_STORAGE -> TownHallBlock.openStorage(player, level, colony.center);
        }
    }
}
