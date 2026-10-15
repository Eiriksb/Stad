package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Request;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * The courier takes the town's requests one by one: fetch it from a warehouse, bring it where it's needed. With no
 * requests waiting, they carry what workers left at their workplaces (a farmhouse's wheat, a fishery's fish) on to the
 * warehouse, or the town hall when there's none. With nothing to carry they keep the warehouse: when the town hall is
 * nearly full they take what it doesn't need (not its food, tools or what the builders are waiting for) to the
 * warehouse, and they move what's on the wrong shelf to its own ({@link dev.eiriksb.stad.building.Warehouse}).
 */
public class CourierGoal extends WorkGoal {
    private Request request;
    /** a workplace chest to empty, when there's no request */
    private ColonyStorage.Box haul;
    /** what to take from it */
    private java.util.function.Predicate<ItemStack> haulWhat = CourierGoal::haulable;
    /** why: for what they say they're doing */
    private String haulWhy = "Fetching from the workplaces";
    /** a warehouse or town hall box they're tidying, and ticks until they look for one again */
    private ColonyStorage.Box tidying;
    private int tidyIn;
    /** the town hall is kept with at least this many slots free */
    static final int HALL_ROOM = 6;

    /** the workplaces whose haul a courier carries on */
    private static final java.util.Set<net.minecraft.resources.ResourceLocation> HAULED = java.util.Set.of(
            dev.eiriksb.stad.building.BuildingDef.FARMHOUSE, dev.eiriksb.stad.building.BuildingDef.LUMBER_CAMP,
            dev.eiriksb.stad.building.BuildingDef.FISHERY, dev.eiriksb.stad.building.BuildingDef.BARN, dev.eiriksb.stad.building.BuildingDef.MINE);

    public CourierGoal(CitizenEntity citizen) {
        super(citizen, Jobs.COURIER);
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return request != null && request.matches(stack); // what's on its way isn't delivered back to the warehouse
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (request != null && colony.requests().contains(request) && request.count > 0) {
            return carrying() > 0 || warehouses(level, colony).count(request::matches) > 0;
        }
        ColonyStorage w = warehouses(level, colony);
        Optional<Request> next = w.isEmpty() ? Optional.empty() : colony.requests().stream()
                .filter(r -> r.courier == null || r.courier.equals(citizen.getUUID()))
                .filter(r -> w.count(r::matches) > 0).findFirst();
        next.ifPresent(r -> r.courier = citizen.getUUID());
        request = next.orElse(null);
        if (request == null) {
            haul = nextHaul(level, colony);
            if (haul != null) {
                haulWhy = "Fetching from the workplaces";
            } else if (tidying != null) {
                haul = tidying;
            } else if (!w.isEmpty() && --tidyIn <= 0) {
                tidyIn = 40; // it looks through every shelf: not every tick
                tidy(level, colony, w);
                haul = tidying;
            }
        }
        return request != null || haul != null;
    }

    /** The nearest workplace chest with something in it, while they have room to carry it. */
    private ColonyStorage.Box nextHaul(ServerLevel level, Colony colony) {
        if (!citizen.inventory().getItem(citizen.inventory().getContainerSize() - 1).isEmpty()) { // their pockets are full
            return null;
        }
        ColonyStorage.Box best = null;
        double bestDist = Double.MAX_VALUE;
        for (dev.eiriksb.stad.colony.Building b : colony.buildings()) {
            if (!b.done() || b.type() == null || !HAULED.contains(b.type())) {
                continue;
            }
            ColonyStorage own = dev.eiriksb.stad.colony.Workplaces.storage(level, b);
            java.util.function.Predicate<ItemStack> stock = stock(b.type());
            boolean spare = own.count(stock) > STOCK; // more than the workplace keeps for itself
            java.util.function.Predicate<ItemStack> what = st -> haulable(st) && (spare || !stock.test(st));
            for (ColonyStorage.Box box : own.boxes()) {
                if (hasHaul(box, what) && box.pos().distSqr(citizen.blockPosition()) < bestDist) {
                    best = box;
                    bestDist = box.pos().distSqr(citizen.blockPosition());
                    haulWhat = what;
                }
            }
        }
        return best;
    }

    /**
     * Something to do in the warehouse: the town hall nearly full, or something on the wrong shelf with room on its
     * own. Sets {@link #tidying} (and what to take) when there is.
     */
    private void tidy(ServerLevel level, Colony colony, ColonyStorage w) {
        if (!citizen.inventory().getItem(citizen.inventory().getContainerSize() - 1).isEmpty() || w.free() < 8) {
            return;
        }
        ColonyStorage hall = ColonyStorage.townHall(level, colony);
        if (!hall.isEmpty() && hall.free() < HALL_ROOM) {
            java.util.Set<net.minecraft.world.item.Item> needed = new java.util.HashSet<>();
            colony.buildings().forEach(b -> needed.addAll(b.missing.keySet()));
            java.util.function.Predicate<ItemStack> spare = st -> !st.isEmpty() && !ColonyStorage.isFood(st)
                    && dev.eiriksb.stad.citizen.Tools.kindOf(st) == null && !(st.getItem() instanceof net.minecraft.world.item.SwordItem)
                    && !needed.contains(st.getItem()) && colony.requests().stream().noneMatch(r -> r.matches(st));
            if (hall.count(spare) > 0) {
                tidying = hall.boxes().getFirst();
                haulWhat = spare;
                haulWhy = "Taking what the town hall doesn't need to the warehouse";
                return;
            }
        }
        for (ColonyStorage.Box box : w.boxes()) {
            dev.eiriksb.stad.building.Warehouse.Shelf here = dev.eiriksb.stad.building.Warehouse.of(box.container());
            if (here == null) {
                continue;
            }
            java.util.function.Predicate<ItemStack> misplaced = st -> {
                if (st.isEmpty()) {
                    return false;
                }
                dev.eiriksb.stad.building.Warehouse.Shelf own = dev.eiriksb.stad.building.Warehouse.of(st);
                return own != here && room(w, own, st);
            };
            for (int i = 0; i < box.container().getContainerSize(); i++) {
                if (misplaced.test(box.container().getItem(i))) {
                    tidying = box;
                    haulWhat = misplaced;
                    haulWhy = "Putting the warehouse in order";
                    return;
                }
            }
        }
    }

    /** Whether a shelf has room for some of a stack: an empty slot, or a stack of it that isn't full. */
    private static boolean room(ColonyStorage w, dev.eiriksb.stad.building.Warehouse.Shelf shelf, ItemStack st) {
        for (ColonyStorage.Box b : w.boxes()) {
            if (dev.eiriksb.stad.building.Warehouse.of(b.container()) != shelf) {
                continue;
            }
            for (int i = 0; i < b.container().getContainerSize(); i++) {
                ItemStack s = b.container().getItem(i);
                if (s.isEmpty() || ItemStack.isSameItemSameComponents(s, st) && s.getCount() < s.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What a workplace chest has to carry on: anything but the tools its workers keep there (PLAN.md §6.9). */
    private static boolean haulable(ItemStack s) {
        return !s.isEmpty() && dev.eiriksb.stad.citizen.Tools.kindOf(s) == null;
    }

    /** how much of its working stock a workplace keeps (the rest is carried on) */
    static final int STOCK = 64;

    /** What a workplace keeps for its own work rather than sending on: the barn's feed, the lumber camp's saplings. */
    static java.util.function.Predicate<ItemStack> stock(net.minecraft.resources.ResourceLocation type) {
        if (dev.eiriksb.stad.building.BuildingDef.BARN.equals(type)) {
            return ShepherdGoal::feed;
        }
        if (dev.eiriksb.stad.building.BuildingDef.LUMBER_CAMP.equals(type)) {
            return st -> st.is(net.minecraft.tags.ItemTags.SAPLINGS);
        }
        return st -> false;
    }

    private static boolean hasHaul(ColonyStorage.Box box, java.util.function.Predicate<ItemStack> what) {
        for (int i = 0; i < box.container().getContainerSize(); i++) {
            if (what.test(box.container().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private static ColonyStorage warehouses(ServerLevel level, Colony colony) {
        return ColonyStorage.warehouses(level, colony);
    }

    private int carrying() {
        int n = 0;
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (request != null && request.matches(inv.getItem(i))) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    @Override
    public void stop() {
        super.stop();
        if (request != null && citizen.getUUID().equals(request.courier)) {
            request.courier = null;
        }
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        return warehouses(level, colony).isEmpty() && colony.buildings().stream().noneMatch(b -> b.done()
                && dev.eiriksb.stad.colony.Workplaces.isWorkplace(b) && !b.is(dev.eiriksb.stad.building.BuildingDef.WAREHOUSE))
                ? "Nothing to carry without a warehouse: build one, and they'll bring what's needed from it" : null;
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Nothing to carry just now";
    }

    @Override
    protected String doingNow() {
        return request != null ? "Carrying " + request.label() + " to the " + request.destName : haulWhy;
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (request == null && haul != null) {
            if (!approach.tick(citizen, haul.pos(), 2.8, 0.65)) {
                return;
            }
            int stacks = 0;
            for (int i = 0; i < haul.container().getContainerSize() && stacks < 4; i++) {
                ItemStack st = haul.container().getItem(i);
                if (haulWhat.test(st)) {
                    pocket(haul.container().removeItemNoUpdate(i));
                    stacks++;
                }
            }
            haul.container().setChanged();
            citizen.swing(InteractionHand.MAIN_HAND);
            citizen.didWork(stacks);
            haul = null;
            tidying = null;
            approach.reset();
            return;
        }
        if (request == null) {
            return;
        }
        if (carrying() == 0) {
            ColonyStorage.Box box = warehouses(level, colony).find(request::matches);
            if (box == null || !approach.tick(citizen, box.pos(), 2.8, 0.65)) {
                return;
            }
            int left = Math.min(request.count, 64 * 4);
            for (int i = 0; i < box.container().getContainerSize() && left > 0; i++) {
                ItemStack s = box.container().getItem(i);
                if (request.matches(s)) {
                    ItemStack taken = s.split(Math.min(left, s.getCount()));
                    left -= taken.getCount();
                    pocket(taken);
                }
            }
            box.container().setChanged();
            citizen.swing(InteractionHand.MAIN_HAND);
            approach.reset();
            return;
        }
        if (!approach.tick(citizen, request.dest, 2.8, 0.65)) {
            return;
        }
        if (!(level.getBlockEntity(request.dest) instanceof Container dest)) {
            colony.removeRequest(request.id);
            request = null;
            return;
        }
        int delivered = 0;
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (request.matches(s)) {
                int before = s.getCount();
                ItemStack rest = ColonyStorage.insert(dest, s);
                delivered += before - rest.getCount();
                inv.setItem(i, rest);
            }
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        citizen.didWork(delivered);
        request.count -= delivered;
        if (request.count <= 0) {
            colony.removeRequest(request.id);
        }
        colony.changed();
        request = null;
        approach.reset();
    }
}
