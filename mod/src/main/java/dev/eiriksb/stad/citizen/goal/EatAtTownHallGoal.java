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

/** A hungry citizen walks to the town hall and eats something from its storage. */
public class EatAtTownHallGoal extends Goal {
    static final float HUNGRY = 8f;
    private final CitizenEntity citizen;
    private BlockPos hall;
    private int repath;
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
        return hall != null && townHall() != null && townHall().hasFood();
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.hunger() < CitizenEntity.MAX_NEED - 4 && townHall() != null && townHall().hasFood();
    }

    private TownHallBlockEntity townHall() {
        return citizen.level().getBlockEntity(hall) instanceof TownHallBlockEntity t ? t : null;
    }

    @Override
    public void start() {
        repath = 0;
        chew = 20;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        citizen.getLookControl().setLookAt(hall.getX() + 0.5, hall.getY() + 0.5, hall.getZ() + 0.5);
        if (citizen.blockPosition().closerToCenterThan(hall.getCenter(), 2.5)) {
            citizen.getNavigation().stop();
            if (--chew <= 0) {
                chew = 30;
                ItemStack food = townHall().takeFood();
                FoodProperties props = food.get(DataComponents.FOOD);
                if (props != null) {
                    citizen.swing(InteractionHand.MAIN_HAND);
                    citizen.playSound(SoundEvents.GENERIC_EAT, 0.6f, 0.9f + citizen.getRandom().nextFloat() * 0.2f);
                    citizen.eat(props.nutrition());
                }
            }
        } else if (--repath <= 0) {
            repath = 40;
            Movement.walkNextTo(citizen, hall, 0.65);
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }

    /** For prompts: whether the town has food at all. */
    public static boolean townHasFood(CitizenEntity c) {
        Colony colony = c.colony().orElse(null);
        return colony != null && c.level().getBlockEntity(colony.center) instanceof TownHallBlockEntity t && t.hasFood();
    }
}
