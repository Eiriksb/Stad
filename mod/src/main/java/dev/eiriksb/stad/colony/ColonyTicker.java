package dev.eiriksb.stad.colony;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Looks after every colony every few seconds: homes, newcomers. */
public final class ColonyTicker {
    private static final int EVERY = 100;
    private static long ticks;

    private ColonyTicker() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ColonyTicker::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        if (++ticks % EVERY != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (Colony colony : ColonyManager.get(server).all()) {
            ServerLevel level = server.getLevel(colony.dimension);
            if (level != null) {
                Homes.update(level, colony);
                Arrivals.tick(level, colony);
            }
        }
    }
}
