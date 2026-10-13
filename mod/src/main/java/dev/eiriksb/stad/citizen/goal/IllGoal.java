package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;

/**
 * An ill citizen goes to bed (PLAN.md §6.9): a free bed in the infirmary, where the healer works, else their own. They
 * get up to eat.
 */
public class IllGoal extends Goal {
    private final CitizenEntity citizen;
    private BlockPos bed;
    private final Movement.Approach approach = new Movement.Approach();

    public IllGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!citizen.ill() || citizen.isNight() || citizen.isSleeping() || citizen.hunger() < 6
                || citizen.colony().map(dev.eiriksb.stad.war.Combat::underAttack).orElse(false)) {
            return false;
        }
        bed = infirmaryBed().orElse(citizen.home().filter(this::free).orElse(null));
        return bed != null;
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.ill() && !citizen.isSleeping() && bed != null && free(bed) && citizen.hunger() >= 6;
    }

    private boolean free(BlockPos pos) {
        BlockState s = citizen.level().getBlockState(pos);
        return s.getBlock() instanceof BedBlock && !s.getValue(BedBlock.OCCUPIED);
    }

    /** The nearest free bed in a finished infirmary. */
    private Optional<BlockPos> infirmaryBed() {
        Colony colony = citizen.colony().orElse(null);
        if (colony == null) {
            return Optional.empty();
        }
        return colony.buildings().stream().filter(b -> b.done() && BuildingDef.INFIRMARY.equals(b.type())).map(Building::placement)
                .flatMap(Optional::stream)
                .flatMap(p -> p.steps().stream().filter(s -> s.state().getBlock() instanceof BedBlock && s.state().getValue(BedBlock.PART) == BedPart.HEAD)
                        .map(s -> s.pos()))
                .filter(this::free).min(Comparator.comparingDouble(p -> p.distSqr(citizen.blockPosition())));
    }

    @Override
    public void start() {
        approach.reset();
    }

    @Override
    public void tick() {
        if (citizen.blockPosition().closerToCenterThan(bed.getCenter(), 2.5)) {
            citizen.getNavigation().stop();
            citizen.startSleeping(bed);
        } else {
            approach.tick(citizen, bed, 2.0, 0.6); // with the shortcut, if they're walled in (a house going up round them)
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }
}
