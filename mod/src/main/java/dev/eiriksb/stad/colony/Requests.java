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
        if (food < Math.max(6, people * 3)) {
            colony.need(Request.FOOD, Math.max(8, people * 4) - food, colony.center, "the town hall", "so nobody goes hungry", now);
        } else {
            colony.request(Request.FOOD, colony.center).ifPresent(r -> colony.removeRequest(r.id));
        }
        // What builders are waiting for, brought to their hut (or the town hall).
        BlockPos dest = builderSupply(level, colony);
        String destName = dest.equals(colony.center) ? "the town hall" : "the builder's hut";
        Set<Integer> wanted = new HashSet<>();
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
            if (!Request.FOOD.equals(r.what) && !wanted.contains(r.id)) {
                colony.removeRequest(r.id);
            }
        }
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
