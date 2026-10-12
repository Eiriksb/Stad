package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.war.Envoys;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** An envoy waits by the other town's hall to be heard, instead of wandering home. */
public class EnvoyGoal extends Goal {
    private final CitizenEntity citizen;
    private BlockPos post;
    private final Movement.Approach approach = new Movement.Approach();

    public EnvoyGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        post = Envoys.post(citizen.getUUID()).orElse(null);
        return post != null;
    }

    @Override
    public boolean canContinueToUse() {
        return Envoys.post(citizen.getUUID()).isPresent();
    }

    @Override
    public void start() {
        approach.reset();
    }

    @Override
    public void tick() {
        if (!Movement.near(citizen, post, 4)) {
            approach.tick(citizen, post, 3, 0.6);
        } else {
            citizen.getNavigation().stop();
        }
    }
}
