package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * A hungry citizen eats: their own food where they stand (what they've been given), or the food in their home's
 * pantry or the town hall, whichever of the two is nearer (and has some).
 */
public class EatAtTownHallGoal extends Goal {
    static final float HUNGRY = 8f;
    private final CitizenEntity citizen;
    /** where they're going to eat (a pantry chest or the town hall), or null when eating their own */
    private BlockPos table;
    private final Movement.Approach approach = new Movement.Approach();
    /** ticks until the next bite (goals don't tick every game tick, so count rather than use tickCount) */
    private int chew;

    public EatAtTownHallGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (citizen.hunger() >= HUNGRY || citizen.isSleeping()) {
            return false;
        }
        return choose();
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.hunger() < CitizenEntity.MAX_NEED - 4 && (ownFood() >= 0 || table != null && foodAt(table));
    }

    /** Picks where to eat; false when there's nothing to eat anywhere. */
    private boolean choose() {
        if (ownFood() >= 0) {
            table = null;
            return true;
        }
        table = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : tables()) {
            double d = p.distSqr(citizen.blockPosition());
            if (d < best && foodAt(p)) {
                best = d;
                table = p;
            }
        }
        return table != null;
    }

    /** Where they might eat: the chests of their house, and the town hall. */
    private List<BlockPos> tables() {
        Colony colony = citizen.colony().orElse(null);
        if (colony == null || !(citizen.level() instanceof ServerLevel level)) {
            return List.of();
        }
        List<BlockPos> out = new java.util.ArrayList<>();
        citizen.record().flatMap(r -> Homes.houseOf(colony, r)).ifPresent(h -> Homes.pantry(level, h).boxes().forEach(b -> out.add(b.pos())));
        out.add(colony.center);
        return out;
    }

    private boolean foodAt(BlockPos p) {
        var be = citizen.level().getBlockEntity(p);
        if (be instanceof TownHallBlockEntity hall) {
            return hall.hasFood();
        }
        return be instanceof net.minecraft.world.Container c && ColonyStorage.of(new ColonyStorage.Box(p, c)).count(Homes::edible) > 0;
    }

    private ItemStack takeAt(BlockPos p) {
        var be = citizen.level().getBlockEntity(p);
        if (be instanceof TownHallBlockEntity hall) {
            return hall.takeFood();
        }
        if (be instanceof net.minecraft.world.Container c) {
            List<ItemStack> got = ColonyStorage.of(new ColonyStorage.Box(p, c)).takeUpTo(Homes::edible, 1);
            return got.isEmpty() ? ItemStack.EMPTY : got.getFirst();
        }
        return ItemStack.EMPTY;
    }

    /** The slot of something to eat in their own things, or -1. */
    private int ownFood() {
        var own = citizen.belongings();
        for (int i = 0; i < own.getContainerSize(); i++) {
            if (Homes.edible(own.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void start() {
        approach.reset();
        chew = 20;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        int own = ownFood();
        if (own >= 0) {
            citizen.getNavigation().stop();
            if (--chew <= 0) {
                chew = 30;
                bite(citizen.belongings().getItem(own).split(1));
            }
            return;
        }
        if (table == null) {
            return;
        }
        citizen.getLookControl().setLookAt(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5);
        if (citizen.blockPosition().closerToCenterThan(table.getCenter(), 2.5)) {
            citizen.getNavigation().stop();
            if (--chew <= 0) {
                chew = 30;
                bite(takeAt(table));
            }
        } else {
            approach.tick(citizen, table, 2.0, 0.65); // with the shortcut, if they're walled in (a house going up round them)
        }
    }

    private void bite(ItemStack food) {
        FoodProperties props = food.get(DataComponents.FOOD);
        if (props != null) {
            citizen.swing(InteractionHand.MAIN_HAND);
            citizen.playSound(SoundEvents.GENERIC_EAT, 0.6f, 0.9f + citizen.getRandom().nextFloat() * 0.2f);
            citizen.eat(props.nutrition());
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }

    /** For prompts: whether there's food for them at all (their own, at home, or the town's). */
    public static boolean townHasFood(CitizenEntity c) {
        return new EatAtTownHallGoal(c).choose();
    }
}
