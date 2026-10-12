package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;

/** Newcomers move into towns that have a free bed for them (PLAN.md §6.1, "Recruitment"). Server thread. */
public final class Arrivals {
    private Arrivals() {}

    static long intervalTicks() {
        return StadConfig.ARRIVAL_MINUTES.get() * 60L * 20L;
    }

    static void tick(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now - colony.lastArrival < intervalTicks() || !level.isLoaded(colony.center)) {
            return;
        }
        if (Homes.freeBeds(level, colony) <= colony.citizens().stream().filter(c -> c.home == null).count()) {
            return; // nowhere for a newcomer to sleep
        }
        colony.lastArrival = now;
        double angle = level.random.nextDouble() * Math.PI * 2;
        int dist = 8 + level.random.nextInt(9);
        BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                colony.center.offset((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist)));
        CitizenEntity c = StadRegistries.CITIZEN.get().create(level);
        if (c == null || !level.isLoaded(at)) {
            return;
        }
        c.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        c.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        level.addFreshEntity(c);
        ColonyManager.get(level.getServer()).join(colony, c);
        Homes.update(level, colony);
        Announce.members(level.getServer(), colony,
                Component.literal(c.getName().getString() + " has moved to " + colony.name() + ".").withStyle(ChatFormatting.GREEN));
        CitizenEntity newcomer = c;
        level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount() + 60, () ->
                dev.eiriksb.stad.twt.StadConversations.speakUp(newcomer, 40, "(You just moved to " + colony.name()
                        + " and see {player}, who lives here. Introduce yourself in a sentence or two.)")));
    }
}
