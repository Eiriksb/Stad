package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/** A hungry citizen eats their own food where they are (what they've been given), or walks to the town hall and eats from its storage. */
public class EatAtTownHallGoal extends Goal {
    static final float HUNGRY = 8f;
    private final CitizenEntity citizen;
    private BlockPos hall;
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
        hall = citizen.colony().map(c -> c.center).orElse(null);
        return ownFood() >= 0 || hall != null && townHall() != null && townHall().hasFood();
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.hunger() < CitizenEntity.MAX_NEED - 4 && (ownFood() >= 0 || hall != null && townHall() != null && townHall().hasFood());
    }

    /** The slot of something to eat in their own things, or -1. */
    private int ownFood() {
        var own = citizen.belongings();
        for (int i = 0; i < own.getContainerSize(); i++) {
            FoodProperties f = own.getItem(i).get(DataComponents.FOOD);
            if (f != null && f.effects().isEmpty()) { // not rotten flesh or a suspicious stew
                return i;
            }
        }
        return -1;
    }

    private TownHallBlockEntity townHall() {
        return citizen.level().getBlockEntity(hall) instanceof TownHallBlockEntity t ? t : null;
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
        if (hall == null || townHall() == null) {
            return;
        }
        citizen.getLookControl().setLookAt(hall.getX() + 0.5, hall.getY() + 0.5, hall.getZ() + 0.5);
        if (citizen.blockPosition().closerToCenterThan(hall.getCenter(), 2.5)) {
            citizen.getNavigation().stop();
            if (--chew <= 0) {
                chew = 30;
                bite(townHall().takeFood());
            }
        } else {
            approach.tick(citizen, hall, 2.0, 0.65); // with the shortcut, if they're walled in (a house going up round them)
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

    /** For prompts: whether there's food for them at all (their own, or the town's). */
    public static boolean townHasFood(CitizenEntity c) {
        if (new EatAtTownHallGoal(c).ownFood() >= 0) {
            return true;
        }
        Colony colony = c.colony().orElse(null);
        return colony != null && c.level().getBlockEntity(colony.center) instanceof TownHallBlockEntity t && t.hasFood();
    }
}
