package dev.eiriksb.stad.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * For now a home is a bed (houses come with buildings in M3). Beds are found through the game's point-of-interest
 * index, which tracks every bed's head. Server thread.
 */
public final class Homes {
    private Homes() {}

    /** The heads of every bed in town, nearest the town hall first. */
    public static List<BlockPos> beds(ServerLevel level, Colony colony) {
        return level.getPoiManager()
                .findAll(type -> type.is(PoiTypes.HOME), pos -> true, colony.center, colony.radius, PoiManager.Occupancy.ANY)
                .map(BlockPos::immutable)
                .sorted(Comparator.comparingLong(colony::horizontalDistSqr))
                .toList();
    }

    /** Frees homes whose bed is gone and gives homeless citizens the free bed nearest the town hall. */
    public static void update(ServerLevel level, Colony colony) {
        if (!level.dimension().equals(colony.dimension)) {
            return;
        }
        List<BlockPos> beds = beds(level, colony);
        Set<BlockPos> present = new HashSet<>(beds);
        boolean changed = false;
        for (CitizenRecord c : colony.citizens()) {
            if (c.home != null && level.isLoaded(c.home) && !present.contains(c.home)) {
                c.home = null;
                changed = true;
            }
        }
        Set<BlockPos> taken = colony.citizens().stream().map(c -> c.home).filter(p -> p != null).collect(Collectors.toSet());
        for (CitizenRecord c : colony.citizens()) {
            if (c.home == null) {
                for (BlockPos bed : beds) {
                    if (taken.add(bed)) {
                        c.home = bed;
                        changed = true;
                        break;
                    }
                }
            }
        }
        if (changed) {
            colony.changed();
        }
    }

    public static int freeBeds(ServerLevel level, Colony colony) {
        long homed = colony.citizens().stream().filter(c -> c.home != null).count();
        return (int) Math.max(0, beds(level, colony).size() - homed);
    }
}
