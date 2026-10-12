package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** In the evening citizens drift to the town hall to see each other (where They Will Talk's ambient chats happen). */
public class GatherAtTownHallGoal extends Goal {
    private final CitizenEntity citizen;
    private BlockPos hall;

    public GatherAtTownHallGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean evening() {
        long t = citizen.level().getDayTime() % 24000;
        return t >= 10500 && t < 12500;
    }

    @Override
    public boolean canUse() {
        if (!evening() || citizen.getRandom().nextInt(40) != 0) {
            return false;
        }
        hall = citizen.colony().map(c -> c.center).orElse(null);
        return hall != null && !citizen.blockPosition().closerToCenterThan(hall.getCenter(), 6);
    }

    @Override
    public boolean canContinueToUse() {
        return evening() && !citizen.getNavigation().isDone();
    }

    @Override
    public void start() {
        int dx = citizen.getRandom().nextInt(7) - 3;
        int dz = citizen.getRandom().nextInt(7) - 3;
        citizen.getNavigation().moveTo(hall.getX() + 0.5 + dx, hall.getY(), hall.getZ() + 0.5 + dz, 0.55);
    }
}
