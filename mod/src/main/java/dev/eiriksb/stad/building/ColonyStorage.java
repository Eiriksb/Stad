package dev.eiriksb.stad.building;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the town keeps its things: the town hall, plus the chests and barrels in finished builder's huts and
 * warehouses. Builders take their materials from here. Server thread.
 */
public final class ColonyStorage {
    private final List<Container> containers;

    private ColonyStorage(List<Container> containers) {
        this.containers = containers;
    }

    public static ColonyStorage of(ServerLevel level, Colony colony) {
        List<Container> found = new ArrayList<>();
        if (level.getBlockEntity(colony.center) instanceof TownHallBlockEntity hall) {
            found.add(hall);
        }
        for (Building b : colony.buildings()) {
            boolean stores = b.def().map(d -> d.type().equals(BuildingDef.BUILDER_HUT) || d.type().equals(BuildingDef.WAREHOUSE)).orElse(false);
            if (b.done() && stores) {
                b.placement().ifPresent(p -> BlockPos.betweenClosedStream(p.bounds()).forEach(pos -> {
                    if (level.getBlockEntity(pos) instanceof Container c && !(c instanceof TownHallBlockEntity)) {
                        found.add(c);
                    }
                }));
            }
        }
        return new ColonyStorage(found);
    }

    public int count(Item item) {
        int n = 0;
        for (Container c : containers) {
            n += c.countItem(item);
        }
        return n;
    }

    /** Takes {@code count} of {@code item} if there's that much in total; otherwise takes nothing. */
    public boolean take(Item item, int count) {
        if (count(item) < count) {
            return false;
        }
        int left = count;
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.is(item)) {
                    int n = Math.min(left, s.getCount());
                    s.shrink(n);
                    left -= n;
                    c.setChanged();
                }
            }
        }
        return true;
    }

    /** Puts something into storage; what doesn't fit is returned. */
    public ItemStack put(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) {
                    c.setItem(i, rest.copy());
                    rest = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(s, rest) && s.getCount() < s.getMaxStackSize()) {
                    int n = Math.min(rest.getCount(), s.getMaxStackSize() - s.getCount());
                    s.grow(n);
                    rest.shrink(n);
                }
                c.setChanged();
            }
        }
        return rest;
    }
}
