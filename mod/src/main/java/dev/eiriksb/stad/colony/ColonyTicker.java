package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.agent.DayPlanner;
import dev.eiriksb.stad.agent.Leadership;
import dev.eiriksb.stad.agent.Social;
import dev.eiriksb.stad.building.Outlines;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Looks after every colony every few seconds (homes, newcomers) and draws building outlines. */
public final class ColonyTicker {
    private static final int EVERY = 100;
    private static long ticks;

    private ColonyTicker() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ColonyTicker::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
            dev.eiriksb.stad.planner.Drafts.clearAll();
            dev.eiriksb.stad.architect.Tables.clearAll();
            dev.eiriksb.stad.war.Raids.clearAll();
            dev.eiriksb.stad.war.Wars.clearAll();
            dev.eiriksb.stad.war.Envoys.clearAll();
            dev.eiriksb.stad.agent.ThinkBudget.clear();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
            dev.eiriksb.stad.pack.Scans.forget(e.getEntity().getUUID());
            dev.eiriksb.stad.architect.Tables.forget(e.getEntity().getUUID());
            dev.eiriksb.stad.pack.PackEditor.forget(e.getEntity().getUUID());
        });
    }

    private static void tick(ServerTickEvent.Post event) {
        long t = dev.eiriksb.stad.perf.Perf.start();
        try {
            upkeep(event.getServer());
        } finally {
            dev.eiriksb.stad.perf.Perf.end("town upkeep", t);
            dev.eiriksb.stad.perf.Perf.nextTick();
        }
    }

    private static void upkeep(MinecraftServer server) {
        if (++ticks % 10 == 0) {
            List<Colony> all = List.copyOf(ColonyManager.get(server).all());
            for (ServerLevel level : server.getAllLevels()) {
                Outlines.draw(level, all);
            }
        }
        if (ticks % 20 == 0) {
            dev.eiriksb.stad.pack.Scans.tick(server);
            dev.eiriksb.stad.architect.Tables.tick(server);
            dev.eiriksb.stad.war.Wars.tick(server, System.currentTimeMillis());
        }
        if (ticks % EVERY != 0) {
            return;
        }
        dev.eiriksb.stad.planner.Drafts.tick(server);
        dev.eiriksb.stad.war.Envoys.tick(server);
        ColonyChunks.update(server);
        for (Colony colony : ColonyManager.get(server).all()) {
            ServerLevel level = server.getLevel(colony.dimension);
            if (level != null) {
                Homes.update(level, colony);
                Workplaces.assign(colony);
                Arrivals.tick(level, colony);
                Requests.update(level, colony);
                Leadership.checkConditions(level, colony);
                dev.eiriksb.stad.war.Raids.tick(level, colony);
                if (level.getDayTime() % 24000 <= 4000 && colony.lastReviewDay != level.getDayTime() / 24000) {
                    dev.eiriksb.stad.citizen.Illness.morning(level, colony);
                    Leadership.review(level, colony);
                    Social.morning(level, colony);
                }
                DayPlanner.tick(level, colony);
            }
        }
    }
}
