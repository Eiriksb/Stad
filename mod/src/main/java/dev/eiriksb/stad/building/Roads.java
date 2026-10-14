package dev.eiriksb.stad.building;

import dev.eiriksb.stad.architect.Yard;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The town's roads (PLAN.md §6.11): where its finished roads are paved (their {@code stad:road} markers, one on each
 * paved block), so citizens can walk faster on them. Worked out again every few seconds, as roads get built.
 */
public final class Roads {
    private Roads() {}

    /** how much faster citizens walk on a road */
    public static final double FASTER = 0.3;
    private static final long REFRESH = 100;

    private record Known(long at, int buildings, Set<Long> cells) {}

    private static final Map<Colony, Known> known = new WeakHashMap<>();

    /** Every paved block of the town's finished roads. */
    public static synchronized Set<Long> cells(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        Known k = known.get(colony);
        if (k == null || now - k.at() >= REFRESH || now < k.at() || k.buildings() != colony.buildings().size()) {
            Set<Long> cells = new HashSet<>();
            for (Building b : colony.buildings()) {
                if (b.done() && b.is(BuildingDef.ROAD)) {
                    b.placement().ifPresent(p -> p.markers("stad:road").forEach(m -> cells.add(m.asLong())));
                }
            }
            k = new Known(now, colony.buildings().size(), cells);
            known.put(colony, k);
        }
        return k.cells();
    }

    /** Whether {@code ground} (the block under someone's feet) is the town's road, and still paved. */
    public static boolean on(ServerLevel level, Colony colony, BlockPos ground) {
        return cells(level, colony).contains(ground.asLong()) && Yard.paving(level.getBlockState(ground));
    }
}
