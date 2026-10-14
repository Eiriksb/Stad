package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Requirements;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where each job works from (PLAN.md §6.8): farmers from a farmhouse, fishers from a fishery... A workplace has room
 * for a few workers (one per work marker, else one per job block, at most four); workers are put in the nearest one
 * of their kind with room, and stay there until they change job or it's gone. Server thread.
 */
public final class Workplaces {
    private Workplaces() {}

    public static final int MAX_SLOTS = 4;

    private static final Map<String, List<ResourceLocation>> FOR_JOB = Map.ofEntries(
            Map.entry(Jobs.FARMER, List.of(BuildingDef.FARMHOUSE)),
            Map.entry(Jobs.LUMBERJACK, List.of(BuildingDef.LUMBER_CAMP)),
            Map.entry(Jobs.FISHER, List.of(BuildingDef.FISHERY)),
            Map.entry(Jobs.SHEPHERD, List.of(BuildingDef.BARN)),
            Map.entry(Jobs.COOK, List.of(BuildingDef.KITCHEN)),
            Map.entry(Jobs.MINER, List.of(BuildingDef.MINE)),
            Map.entry(Jobs.BUILDER, List.of(BuildingDef.BUILDER_HUT)),
            Map.entry(Jobs.COURIER, List.of(BuildingDef.WAREHOUSE)),
            Map.entry(Jobs.GUARD, List.of(BuildingDef.BARRACKS, BuildingDef.WATCHTOWER)),
            Map.entry(Jobs.SMITH, List.of(BuildingDef.SMITHY)),
            Map.entry(Jobs.HEALER, List.of(BuildingDef.INFIRMARY)),
            Map.entry(Jobs.MERCHANT, List.of(BuildingDef.MARKET, BuildingDef.STALLS)),
            Map.entry(Jobs.TEACHER, List.of(BuildingDef.SCHOOL)),
            Map.entry(Jobs.RESEARCHER, List.of(BuildingDef.UNIVERSITY)),
            Map.entry(Jobs.CARPENTER, List.of(BuildingDef.SAWMILL)),
            Map.entry(Jobs.MASON, List.of(BuildingDef.STONEWORKS)),
            Map.entry(Jobs.WEAVER, List.of(BuildingDef.LOOM_HOUSE)),
            Map.entry(Jobs.BEEKEEPER, List.of(BuildingDef.APIARY)));

    /** The kinds of building a job works from (empty: none). */
    public static List<ResourceLocation> kinds(String job) {
        return job == null ? List.of() : FOR_JOB.getOrDefault(job, List.of());
    }

    /** The job that works from a kind of building, if any. */
    public static Optional<String> jobFor(ResourceLocation type) {
        return FOR_JOB.entrySet().stream().filter(e -> e.getValue().contains(type)).map(Map.Entry::getKey).findFirst();
    }

    public static boolean isWorkplace(Building b) {
        return b.type() != null && jobFor(b.type()).isPresent() && !b.isRepair();
    }

    /** What makes a building of this kind a place for one more worker. */
    private static Predicate<BlockState> jobBlock(ResourceLocation type) {
        if (type.equals(BuildingDef.FARMHOUSE)) {
            return s -> s.is(Blocks.COMPOSTER);
        } else if (type.equals(BuildingDef.LUMBER_CAMP)) {
            return s -> s.is(Blocks.CRAFTING_TABLE);
        } else if (type.equals(BuildingDef.FISHERY)) {
            return s -> s.is(Blocks.BARREL);
        } else if (type.equals(BuildingDef.BARN)) {
            return s -> s.is(Blocks.LOOM);
        } else if (type.equals(BuildingDef.KITCHEN)) {
            return s -> s.getBlock() instanceof AbstractFurnaceBlock || s.getBlock() instanceof CampfireBlock;
        } else if (type.equals(BuildingDef.SMITHY)) {
            return s -> s.getBlock() instanceof net.minecraft.world.level.block.AnvilBlock;
        } else if (type.equals(BuildingDef.INFIRMARY)) {
            return s -> s.is(Blocks.BREWING_STAND);
        } else if (type.equals(BuildingDef.MARKET)) {
            return s -> s.is(Blocks.BELL);
        } else if (type.equals(BuildingDef.SAWMILL)) {
            return s -> s.is(Blocks.CRAFTING_TABLE);
        } else if (type.equals(BuildingDef.STONEWORKS)) {
            return s -> s.is(Blocks.STONECUTTER);
        } else if (type.equals(BuildingDef.LOOM_HOUSE)) {
            return s -> s.is(Blocks.LOOM);
        } else if (type.equals(BuildingDef.APIARY)) {
            return s -> false; // one beekeeper looks after all the hives
        } else if (type.equals(BuildingDef.STALLS)) {
            return s -> s.is(Blocks.BARREL); // one behind each stall's counter
        } else if (type.equals(BuildingDef.SCHOOL) || type.equals(BuildingDef.UNIVERSITY)) {
            return s -> s.is(Blocks.LECTERN);
        } else if (type.equals(BuildingDef.BARRACKS)) {
            return s -> s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD;
        }
        return s -> false;
    }

    /** How many can work there. */
    public static int slots(Building b) {
        ResourceLocation type = b.type();
        Optional<Placement> p = b.placement();
        if (type == null || p.isEmpty()) {
            return 0;
        }
        int marked = p.get().markers("stad:work").size();
        if (marked > 0) {
            return Math.min(MAX_SLOTS, marked);
        }
        if (type.equals(BuildingDef.WAREHOUSE)) {
            return 2;
        }
        Predicate<BlockState> job = jobBlock(type);
        int n = (int) p.get().steps().stream().filter(s -> job.test(s.state())).count();
        return Math.max(1, Math.min(MAX_SLOTS, n));
    }

    /** The workplace a citizen has, while it's still a finished building of their job's kind. */
    public static Optional<Building> of(Colony colony, CitizenRecord r) {
        if (r.workplace == null) {
            return Optional.empty();
        }
        return colony.building(r.workplace).filter(b -> b.done() && b.type() != null && kinds(r.job).contains(b.type()));
    }

    public static List<CitizenRecord> workers(Colony colony, Building b) {
        return colony.citizens().stream().filter(r -> r.workplace != null && r.workplace == b.id && of(colony, r).isPresent()).toList();
    }

    /** Gives workers without one the nearest workplace of their kind with room, and lets go of ones that aren't any more. */
    public static void assign(Colony colony) {
        boolean changed = false;
        for (CitizenRecord r : colony.citizens()) {
            if (r.workplace != null && of(colony, r).isEmpty()) {
                r.workplace = null;
                changed = true;
            }
        }
        for (CitizenRecord r : colony.citizens()) {
            List<ResourceLocation> kinds = kinds(r.job);
            if (r.workplace != null || kinds.isEmpty()) {
                continue;
            }
            BlockPos from = r.home != null ? r.home : colony.center;
            Optional<Building> free = colony.buildings().stream()
                    .filter(b -> b.done() && b.type() != null && kinds.contains(b.type()) && workers(colony, b).size() < slots(b))
                    .min(Comparator.comparingDouble(b -> spot(b).distSqr(from)));
            if (free.isPresent()) {
                r.workplace = free.get().id;
                changed = true;
            }
        }
        if (changed) {
            colony.changed();
        }
    }

    /** Moves a worker to a workplace; returns why not, or null. */
    public static String move(Colony colony, CitizenRecord r, Building b) {
        if (r.job == null) {
            return r.name + " has no job.";
        }
        if (!b.done()) {
            return "The " + b.name + " isn't finished.";
        }
        if (b.type() == null || !kinds(r.job).contains(b.type())) {
            return "The " + b.name + " isn't a place for a " + r.job + ".";
        }
        if (!(r.workplace != null && r.workplace == b.id) && workers(colony, b).size() >= slots(b)) {
            return "The " + b.name + " has no room for another " + r.job + ".";
        }
        r.workplace = b.id;
        r.noWorkplaceDays = 0;
        colony.changed();
        return null;
    }

    /** Where a worker goes to start their day: a work marker, else the middle of the building's floor. */
    public static BlockPos spot(Building b) {
        return spot(b, 0);
    }

    /** The {@code n}th worker's spot: their own work marker when there are several (a stall each at the market). */
    public static BlockPos spot(Building b, int n) {
        return b.placement().map(p -> {
            List<BlockPos> work = p.markers("stad:work");
            if (!work.isEmpty()) {
                return work.get(Math.floorMod(n, work.size()));
            }
            var box = p.bounds();
            return new BlockPos((box.minX() + box.maxX()) / 2, box.minY() + 1, (box.minZ() + box.maxZ()) / 2);
        }).orElse(b.origin);
    }

    /** The containers in a workplace (where its workers take in their haul). */
    public static ColonyStorage storage(ServerLevel level, Building b) {
        List<ColonyStorage.Box> boxes = new ArrayList<>();
        b.placement().ifPresent(p -> BlockPos.betweenClosedStream(p.bounds()).forEach(pos -> {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c && !(be instanceof dev.eiriksb.stad.block.TownHallBlockEntity)) {
                boxes.add(new ColonyStorage.Box(pos.immutable(), c));
            }
        }));
        return ColonyStorage.of(boxes);
    }

    /** "the Lakeside Fishery", for prompts and screens. */
    public static String describe(Colony colony, CitizenRecord r) {
        return of(colony, r).map(b -> "the " + b.name).orElse(null);
    }

    /** The kind of building a job would like to work from ("a fishery"), or null. */
    public static String wanted(String job) {
        List<ResourceLocation> k = kinds(job);
        if (k.isEmpty()) {
            return null;
        }
        String w = Requirements.word(k.getFirst());
        return ("aeiou".indexOf(w.charAt(0)) >= 0 ? "an " : "a ") + w;
    }
}
