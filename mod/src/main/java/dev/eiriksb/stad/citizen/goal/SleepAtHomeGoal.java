package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/** At night, a citizen with a home walks to their bed and sleeps until morning. */
public class SleepAtHomeGoal extends Goal {
    private final CitizenEntity citizen;
    private BlockPos bed;
    private final Movement.Approach approach = new Movement.Approach();

    public SleepAtHomeGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isNight() || citizen.isSleeping() || citizen.hunger() < 2 || dev.eiriksb.stad.war.Combat.onWatch(citizen)) {
            return false;
        }
        bed = citizen.home().orElse(null);
        return bed != null && free(bed);
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.isNight() && !citizen.isSleeping() && bed != null && bed.equals(citizen.home().orElse(null)) && free(bed);
    }

    private boolean free(BlockPos pos) {
        BlockState s = citizen.level().getBlockState(pos);
        return s.getBlock() instanceof BedBlock && !s.getValue(BedBlock.OCCUPIED);
    }

    @Override
    public void start() {
        approach.reset();
    }

    @Override
    public void tick() {
        // Players can get into a bed from about three blocks away; citizens from two and a half.
        if (citizen.blockPosition().closerToCenterThan(bed.getCenter(), 2.5)) {
            citizen.getNavigation().stop();
            citizen.startSleeping(bed);
        } else {
            approach.tick(citizen, bed, 2.0, 0.7); // with the shortcut, if they're walled in (a house going up round them)
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }
}
