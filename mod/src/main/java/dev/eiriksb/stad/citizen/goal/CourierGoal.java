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
 * warehouse, or the town hall when there's none.
 */
public class CourierGoal extends WorkGoal {
    private Request request;
    /** a workplace chest to empty, when there's no request */
    private ColonyStorage.Box haul;

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
            for (ColonyStorage.Box box : dev.eiriksb.stad.colony.Workplaces.storage(level, b).boxes()) {
                if (hasHaul(box) && box.pos().distSqr(citizen.blockPosition()) < bestDist) {
                    best = box;
                    bestDist = box.pos().distSqr(citizen.blockPosition());
                }
            }
        }
        return best;
    }

    /** What a workplace chest has to carry on: anything but the tools its workers keep there (PLAN.md §6.9). */
    private static boolean haulable(ItemStack s) {
        return !s.isEmpty() && dev.eiriksb.stad.citizen.Tools.kindOf(s) == null;
    }

    private static boolean hasHaul(ColonyStorage.Box box) {
        for (int i = 0; i < box.container().getContainerSize(); i++) {
            if (haulable(box.container().getItem(i))) {
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
    protected void work(ServerLevel level, Colony colony) {
        if (request == null && haul != null) {
            if (!approach.tick(citizen, haul.pos(), 2.8, 0.65)) {
                return;
            }
            int stacks = 0;
            for (int i = 0; i < haul.container().getContainerSize() && stacks < 4; i++) {
                ItemStack st = haul.container().getItem(i);
                if (haulable(st)) {
                    pocket(haul.container().removeItemNoUpdate(i));
                    stacks++;
                }
            }
            haul.container().setChanged();
            citizen.swing(InteractionHand.MAIN_HAND);
            citizen.didWork(stacks);
            haul = null;
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
