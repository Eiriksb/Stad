package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What's left to do on a building site, and what it still needs. Server thread. */
public final class Construction {
    private Construction() {}

    /** Whether a step is already done in the world (same block; the details may differ, like how stairs join up). */
    public static boolean satisfied(ServerLevel level, Placement.Step step) {
        BlockState now = level.getBlockState(step.pos());
        if (step.clearing()) {
            return now.isAir() || (now.canBeReplaced() && now.getFluidState().isEmpty());
        }
        return now.is(step.state().getBlock());
    }

    /** Moves {@code building.progress} past steps that are already done; returns the next step, or null when finished. */
    public static Placement.Step next(ServerLevel level, Building building, Placement placement) {
        List<Placement.Step> steps = placement.steps();
        while (building.progress < steps.size() && satisfied(level, steps.get(building.progress))) {
            building.progress++;
        }
        return building.progress < steps.size() ? steps.get(building.progress) : null;
    }

    /** What the rest of the building needs that the town doesn't have, item by item. */
    public static Map<Item, Integer> missing(ServerLevel level, Building building, Placement placement, ColonyStorage storage) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        List<Placement.Step> steps = placement.steps();
        for (int i = building.progress; i < steps.size(); i++) {
            Placement.Step s = steps.get(i);
            if (s.count() > 0 && !satisfied(level, s)) {
                needed.merge(s.item(), s.count(), Integer::sum);
            }
        }
        Map<Item, Integer> missing = new LinkedHashMap<>();
        needed.forEach((item, n) -> {
            int short_ = n - storage.count(item);
            if (short_ > 0) {
                missing.put(item, short_);
            }
        });
        return missing;
    }

    /** "12 oak planks, 4 glass panes and 2 red beds" */
    public static String describe(Map<Item, Integer> items, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<Item, Integer> e : items.entrySet()) {
            if (i == max) {
                sb.append(" and ").append(items.size() - max).append(" more");
                break;
            }
            if (i > 0) {
                sb.append(i == items.size() - 1 ? " and " : ", ");
            }
            sb.append(e.getValue()).append(' ').append(e.getKey().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
            i++;
        }
        return sb.toString();
    }

    public static boolean inside(BlockPos pos, Placement p) {
        return p.bounds().isInside(pos);
    }
}
