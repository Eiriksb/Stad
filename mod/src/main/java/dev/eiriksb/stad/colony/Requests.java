package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Keeps a town's requests in step with what it needs. Server thread, every few seconds. */
public final class Requests {
    private Requests() {}

    public static void update(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        // Food for the town hall, where citizens eat.
        int people = Math.max(1, colony.population());
        int food = level.getBlockEntity(colony.center) instanceof TownHallBlockEntity hall
                ? countFood(hall) : 0;
        if (food < hallMinimum(colony)) {
            colony.need(Request.FOOD, Math.max(8, people * 4) - food, colony.center, "the town hall", "so nobody goes hungry", now);
        } else {
            colony.request(Request.FOOD, colony.center).ifPresent(r -> colony.removeRequest(r.id));
        }
        Set<Integer> wanted = new HashSet<>();
        // Food for each house's pantry, for the people who sleep there.
        for (Building house : colony.buildings()) {
            if (!house.done()) {
                continue;
            }
            java.util.List<CitizenRecord> residents = Homes.residents(colony, house);
            ColonyStorage pantry = residents.isEmpty() ? null : Homes.pantry(level, house);
            if (pantry == null || pantry.isEmpty()) {
                continue;
            }
            int stocked = pantry.count(Homes::edible);
            java.util.Optional<Request> asked = colony.request(Request.FOOD, pantry.boxes().getFirst().pos());
            if (stocked < residents.size() * Homes.PANTRY_LOW || asked.isPresent() && stocked < residents.size() * Homes.PANTRY_FULL) {
                String who = residents.size() == 1 ? residents.getFirst().name.split(" ")[0] : "the " + residents.size() + " who live there";
                wanted.add(colony.need(Request.FOOD, residents.size() * Homes.PANTRY_FULL - stocked, pantry.boxes().getFirst().pos(), "the " + house.name,
                        "for " + who + " to eat at home", now).id);
            }
        }
        // What builders are waiting for, brought to their hut (or the town hall).
        BlockPos dest = builderSupply(level, colony);
        String destName = dest.equals(colony.center) ? "the town hall" : "the builder's hut";
        for (Building b : colony.buildings()) {
            if (b.status == Building.Status.WAITING) {
                for (Map.Entry<Item, Integer> m : b.missing.entrySet()) {
                    wanted.add(colony.need(Request.id(m.getKey()), m.getValue(), dest, destName, "for the " + b.name, now).id);
                }
            }
        }
        // a sword for each guard without one
        int unarmed = 0;
        for (CitizenRecord c : colony.citizens()) {
            if (level.getEntity(c.uuid) instanceof dev.eiriksb.stad.citizen.CitizenEntity e && dev.eiriksb.stad.war.Combat.unarmed(e)) {
                unarmed++;
            }
        }
        if (unarmed > 0) {
            wanted.add(colony.need(Request.id(net.minecraft.world.item.Items.IRON_SWORD), unarmed, colony.center, "the town hall",
                    unarmed == 1 ? "for the guard" : "for the guards", now).id);
        }
        // a tool for each worker who's making do without (PLAN.md §6.9)
        for (dev.eiriksb.stad.citizen.Tools.Kind k : dev.eiriksb.stad.citizen.Tools.Kind.values()) {
            int short_ = k == dev.eiriksb.stad.citizen.Tools.Kind.SWORD ? 0 : dev.eiriksb.stad.citizen.Tools.shortage(level, colony, k);
            if (short_ > 0) {
                wanted.add(colony.need(Request.id(k.basic), short_, colony.center, "the town hall",
                        "for " + dev.eiriksb.stad.citizen.Tools.whoNeeds(level, colony, k), now).id);
            }
        }
        for (Request r : colony.requests()) {
            if (!(Request.FOOD.equals(r.what) && r.dest.equals(colony.center)) && !wanted.contains(r.id)) {
                colony.removeRequest(r.id);
            }
        }
    }

    /** How much food the town hall keeps for everyone (couriers only take its spare food to the houses). */
    public static int hallMinimum(Colony colony) {
        return Math.max(6, Math.max(1, colony.population()) * 3);
    }

    private static int countFood(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (ColonyStorage.isFood(c.getItem(i))) {
                n += c.getItem(i).getCount();
            }
        }
        return n;
    }

    /** A chest in the first finished builder's hut, else the town hall. */
    static BlockPos builderSupply(ServerLevel level, Colony colony) {
        for (ColonyStorage.Box b : ColonyStorage.supply(level, colony).boxes()) {
            if (!b.pos().equals(colony.center)) {
                return b.pos();
            }
        }
        return colony.center;
    }

    public static boolean isBuilderHut(Building b) {
        return b.is(BuildingDef.BUILDER_HUT);
    }
}
