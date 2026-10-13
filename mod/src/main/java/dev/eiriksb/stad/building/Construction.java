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
        if (now.getDestroySpeed(level, step.pos()) < 0) {
            return true; // bedrock and the like: build round it
        }
        if (step.clearing()) {
            // water flowing in from outside comes back however often it's cleared; it stops once the walls are up
            return now.isAir() || (now.canBeReplaced() && (now.getFluidState().isEmpty() || !now.getFluidState().isSource()));
        }
        return now.is(step.state().getBlock());
    }

    /**
     * The step to do now: the next one in order, or, when the town hasn't got what that takes, a later one it has the
     * materials for (but nothing that hangs on other blocks or falls, which waits its turn). Null when nothing can be
     * done until more materials come (or the building's finished: see {@link #next}).
     */
    public static Placement.Step doable(ServerLevel level, Building building, Placement placement, ColonyStorage storage) {
        Placement.Step first = next(level, building, placement);
        if (first == null) {
            return null;
        }
        Map<Item, Integer> have = new java.util.HashMap<>();
        if (affordable(first, storage, have)) {
            return first;
        }
        List<Placement.Step> steps = placement.steps();
        for (int i = building.progress + 1; i < steps.size(); i++) {
            Placement.Step s = steps.get(i);
            if (s.clearing() || Placement.hangsOnOthers(s.state()) || s.state().getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
                continue;
            }
            if (affordable(s, storage, have) && !satisfied(level, s)) {
                return s;
            }
        }
        return null;
    }

    private static boolean affordable(Placement.Step s, ColonyStorage storage, Map<Item, Integer> have) {
        return s.clearing() || s.count() <= 0 || have.computeIfAbsent(s.item(), storage::count) >= s.count();
    }

    /** How many of its steps are done (for how far along it is, now that builders build what they can out of order). */
    public static int done(ServerLevel level, Building building, Placement placement) {
        int n = building.progress;
        List<Placement.Step> steps = placement.steps();
        for (int i = building.progress; i < steps.size(); i++) {
            if (satisfied(level, steps.get(i))) {
                n++;
            }
        }
        return n;
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
