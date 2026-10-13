package dev.eiriksb.stad.building;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Some of the town's containers. The <b>supply</b> is what builders build from and citizens eat from: the town hall
 * and finished builder's huts. <b>Warehouses</b> hold everything else; couriers carry what's needed from them to the
 * supply. Workers deliver to a warehouse, or to the town hall when there's none. Server thread.
 */
public final class ColonyStorage {
    /** A container and where it is. */
    public record Box(BlockPos pos, Container container) {}

    private final List<Box> boxes;

    private ColonyStorage(List<Box> boxes) {
        this.boxes = boxes;
    }

    /** The town hall plus finished builder's huts. */
    public static ColonyStorage supply(ServerLevel level, Colony colony) {
        List<Box> found = new ArrayList<>();
        if (level.getBlockEntity(colony.center) instanceof TownHallBlockEntity hall) {
            found.add(new Box(colony.center, hall));
        }
        found.addAll(containersIn(level, colony, BuildingDef.BUILDER_HUT));
        return new ColonyStorage(found);
    }

    /** One container on its own. */
    public static ColonyStorage of(Box box) {
        return new ColonyStorage(List.of(box));
    }

    /** Just the town hall: where the food goes. */
    public static ColonyStorage townHall(ServerLevel level, Colony colony) {
        List<Box> found = new ArrayList<>();
        if (level.getBlockEntity(colony.center) instanceof TownHallBlockEntity hall) {
            found.add(new Box(colony.center, hall));
        }
        return new ColonyStorage(found);
    }

    /** The chests and barrels of finished warehouses. */
    public static ColonyStorage warehouses(ServerLevel level, Colony colony) {
        return new ColonyStorage(containersIn(level, colony, BuildingDef.WAREHOUSE));
    }

    /** Where workers bring what they make: the warehouses, or the town hall when there are none. */
    public static ColonyStorage deliveries(ServerLevel level, Colony colony) {
        ColonyStorage w = warehouses(level, colony);
        if (!w.boxes.isEmpty()) {
            return w;
        }
        List<Box> hall = new ArrayList<>();
        if (level.getBlockEntity(colony.center) instanceof TownHallBlockEntity t) {
            hall.add(new Box(colony.center, t));
        }
        return new ColonyStorage(hall);
    }

    private static List<Box> containersIn(ServerLevel level, Colony colony, ResourceLocation type) {
        List<Box> found = new ArrayList<>();
        for (Building b : colony.buildings()) {
            if (b.done() && b.is(type)) {
                b.placement().ifPresent(p -> {
                    // with storage markers, only the containers next to them are the town's; without, all of them
                    List<BlockPos> marked = p.markers("stad:storage");
                    BlockPos.betweenClosedStream(p.bounds()).forEach(pos -> {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be instanceof Container c && !(c instanceof TownHallBlockEntity)
                                && (marked.isEmpty() || marked.stream().anyMatch(m -> m.distManhattan(pos) <= 2 && m.closerThan(pos, 1.8)))) {
                            found.add(new Box(pos.immutable(), c));
                        }
                    });
                });
            }
        }
        return found;
    }

    public List<Box> boxes() {
        return boxes;
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    public int count(Item item) {
        return count(s -> s.is(item));
    }

    public int count(Predicate<ItemStack> matches) {
        int n = 0;
        for (Box b : boxes) {
            for (int i = 0; i < b.container.getContainerSize(); i++) {
                ItemStack s = b.container.getItem(i);
                if (!s.isEmpty() && matches.test(s)) {
                    n += s.getCount();
                }
            }
        }
        return n;
    }

    public static boolean isFood(ItemStack s) {
        return s.has(DataComponents.FOOD);
    }

    /** Takes {@code count} of {@code item} if there's that much in total; otherwise takes nothing. */
    public boolean take(Item item, int count) {
        if (count(item) < count) {
            return false;
        }
        takeUpTo(s -> s.is(item), count);
        return true;
    }

    /** Takes up to {@code max} matching items (possibly of different kinds); returns what was taken. */
    public List<ItemStack> takeUpTo(Predicate<ItemStack> matches, int max) {
        List<ItemStack> taken = new ArrayList<>();
        int left = max;
        for (Box b : boxes) {
            for (int i = 0; i < b.container.getContainerSize() && left > 0; i++) {
                ItemStack s = b.container.getItem(i);
                if (!s.isEmpty() && matches.test(s)) {
                    taken.add(s.split(Math.min(left, s.getCount())));
                    left -= taken.getLast().getCount();
                    b.container.setChanged();
                }
            }
        }
        return taken;
    }

    /** The first box holding something matching (where a courier should go to fetch it). */
    public Box find(Predicate<ItemStack> matches) {
        for (Box b : boxes) {
            for (int i = 0; i < b.container.getContainerSize(); i++) {
                ItemStack s = b.container.getItem(i);
                if (!s.isEmpty() && matches.test(s)) {
                    return b;
                }
            }
        }
        return null;
    }

    /** Puts something into storage; what doesn't fit is returned. */
    public ItemStack put(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (Box b : boxes) {
            rest = insert(b.container, rest);
            if (rest.isEmpty()) {
                break;
            }
        }
        return rest;
    }

    /** Puts a stack into one container (merging first); returns what didn't fit. */
    public static ItemStack insert(Container c, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                ItemStack s = c.getItem(i);
                if (pass == 0 && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, rest) && s.getCount() < s.getMaxStackSize()) {
                    int n = Math.min(rest.getCount(), s.getMaxStackSize() - s.getCount());
                    s.grow(n);
                    rest.shrink(n);
                } else if (pass == 1 && s.isEmpty() && c.canPlaceItem(i, rest)) {
                    c.setItem(i, rest.copy());
                    rest = ItemStack.EMPTY;
                }
            }
        }
        c.setChanged();
        return rest;
    }
}
