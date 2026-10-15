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

    /** A bed in {@code building} nobody else sleeps in ({@code citizen}'s own counts as free), or null. */
    public static BlockPos freeBed(ServerLevel level, Colony colony, Building building, java.util.UUID citizen) {
        Set<BlockPos> taken = colony.citizens().stream().filter(c -> c.home != null && !c.uuid.equals(citizen)).map(c -> c.home)
                .collect(Collectors.toSet());
        for (BlockPos bed : beds(level, colony)) {
            if (inside(bed, building) && !taken.contains(bed)) {
                return bed;
            }
        }
        return null;
    }

    /** Whether a bed is in a building. */
    public static boolean inside(BlockPos bed, Building building) {
        return bed != null && !building.ring() && building.placement().map(p -> p.bounds().isInside(bed)).orElse(false);
    }

    /** The town's house a citizen's bed is in, if any. */
    public static java.util.Optional<Building> houseOf(Colony colony, BlockPos bed) {
        return colony.buildings().stream().filter(b -> b.done() && inside(bed, b)).findFirst();
    }

    /** how much food a house keeps per person who sleeps there: topped up to {@link #PANTRY_FULL} when it's under {@link #PANTRY_LOW} */
    public static final int PANTRY_LOW = 3, PANTRY_FULL = 6;

    /** The house a citizen sleeps in, if it's one of the town's buildings. */
    public static java.util.Optional<Building> houseOf(Colony colony, CitizenRecord r) {
        return r.home == null ? java.util.Optional.empty() : houseOf(colony, r.home);
    }

    /** Who sleeps in a house. */
    public static List<CitizenRecord> residents(Colony colony, Building house) {
        return colony.citizens().stream().filter(r -> inside(r.home, house)).toList();
    }

    /** A house's pantry: its chests and barrels, for the people who sleep there to eat from. */
    public static dev.eiriksb.stad.building.ColonyStorage pantry(ServerLevel level, Building house) {
        if (Workplaces.isWorkplace(house)) {
            return dev.eiriksb.stad.building.ColonyStorage.of(List.of()); // a workplace's chests are for its work
        }
        return Workplaces.storage(level, house);
    }

    /** Something fit to eat (not rotten flesh, a pufferfish or a suspicious stew). */
    public static boolean edible(net.minecraft.world.item.ItemStack s) {
        var f = s.get(net.minecraft.core.component.DataComponents.FOOD);
        return f != null && f.effects().isEmpty();
    }

    public static int freeBeds(ServerLevel level, Colony colony) {
        long homed = colony.citizens().stream().filter(c -> c.home != null).count();
        return (int) Math.max(0, beds(level, colony).size() - homed);
    }
}
