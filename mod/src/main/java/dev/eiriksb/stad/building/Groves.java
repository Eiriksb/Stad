package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The town's groves (PLAN.md §6.11), designed at the architect's table: spots marked {@code stad:sapling_oak}... where
 * the lumberjack plants a sapling (that kind when they have it) and fells the tree once it's grown. Server thread.
 */
public final class Groves {
    private Groves() {}

    public static boolean is(Building b) {
        return b.is(BuildingDef.GROVE);
    }

    public static List<Building> of(Colony colony) {
        return colony.buildings().stream().filter(Groves::is).toList();
    }

    /** Every grove's sapling spots, with the tree each wants ("oak"; "" for any). */
    public static Map<BlockPos, String> spots(Colony colony) {
        Map<BlockPos, String> out = new LinkedHashMap<>();
        for (Building g : of(colony)) {
            g.placement().ifPresent(p -> p.markers().stream().filter(m -> m.name().startsWith("stad:sapling"))
                    .forEach(m -> out.put(m.pos(), m.name().length() > "stad:sapling_".length() ? m.name().substring("stad:sapling_".length()) : "")));
        }
        return out;
    }
}
