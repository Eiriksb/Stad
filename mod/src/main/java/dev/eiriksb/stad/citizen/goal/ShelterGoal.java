package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.war.Combat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** While their town is attacked, everyone who doesn't fight hurries home (or to the town hall) and stays there. */
public class ShelterGoal extends Goal {
    private final CitizenEntity citizen;
    private BlockPos shelter;
    private final Movement.Approach approach = new Movement.Approach();

    public ShelterGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Their bed if it's in one of the town's houses; else inside the nearest finished building; else the town hall. */
    private BlockPos shelter(Colony colony) {
        BlockPos bed = citizen.home().orElse(null);
        if (bed != null && dev.eiriksb.stad.colony.Homes.houseOf(colony, bed).isPresent()) {
            return bed;
        }
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (dev.eiriksb.stad.colony.Building b : colony.buildings()) {
            if (!b.done() || b.isRepair() || b.placement().isEmpty()) {
                continue;
            }
            var box = b.placement().get().bounds();
            BlockPos inside = new BlockPos(box.getCenter().getX(), box.minY() + 1, box.getCenter().getZ());
            double d = inside.distSqr(citizen.blockPosition());
            if (d < bestD && Movement.standable((net.minecraft.server.level.ServerLevel) citizen.level(), inside)) {
                best = inside;
                bestD = d;
            }
        }
        return best != null ? best : bed != null ? bed : colony.center;
    }

    @Override
    public boolean canUse() {
        if (citizen.isSleeping() || citizen.tickCount % 20 != 0) {
            return false;
        }
        Colony colony = citizen.colony().orElse(null);
        if (colony == null || !Combat.underAttack(colony) || Combat.fighter(citizen)
                || dev.eiriksb.stad.war.Envoys.isEnvoy(citizen.getUUID())) {
            return false;
        }
        shelter = shelter(colony);
        return !Movement.near(citizen, shelter, 3);
    }

    @Override
    public boolean canContinueToUse() {
        Colony colony = citizen.colony().orElse(null);
        return colony != null && Combat.underAttack(colony) && !Movement.near(citizen, shelter, 2.5);
    }

    @Override
    public void start() {
        approach.reset();
    }

    @Override
    public void tick() {
        approach.tick(citizen, shelter, 2.5, 0.85);
    }
}
