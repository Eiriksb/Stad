package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * In the evening citizens go and see each other: whoever their day plan said, else the town hall, where the others
 * are (and where They Will Talk's ambient chats happen). Being with someone counts as company.
 */
public class GatherAtTownHallGoal extends Goal {
    private final CitizenEntity citizen;
    private BlockPos hall;
    private CitizenEntity friend;

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
        if (citizen.tickCount % 10 == 0) {
            noteCompany();
        }
        friend = visiting();
        hall = friend != null ? friend.blockPosition() : citizen.colony().map(c -> c.center).orElse(null);
        return hall != null && !citizen.blockPosition().closerToCenterThan(hall.getCenter(), friend != null ? 3 : 6);
    }

    /** The citizen their plan says to visit, if they're nearby in this world. */
    private CitizenEntity visiting() {
        String name = citizen.record().map(r -> r.planVisit).orElse(null);
        if (name == null) {
            return null;
        }
        return citizen.level().getEntitiesOfClass(CitizenEntity.class, citizen.getBoundingBox().inflate(64),
                c -> c != citizen && c.getName().getString().equals(name)).stream().findFirst().orElse(null);
    }

    private void noteCompany() {
        var near = citizen.level().getEntitiesOfClass(CitizenEntity.class, citizen.getBoundingBox().inflate(4), c -> c != citizen);
        if (!near.isEmpty()) {
            citizen.hadCompany();
            near.forEach(CitizenEntity::hadCompany);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return evening() && !citizen.getNavigation().isDone();
    }

    @Override
    public void start() {
        if (friend != null) {
            Movement.walkNextTo(citizen, friend.blockPosition(), 0.55);
            return;
        }
        int dx = citizen.getRandom().nextInt(7) - 3;
        int dz = citizen.getRandom().nextInt(7) - 3;
        citizen.getNavigation().moveTo(hall.getX() + 0.5 + dx, hall.getY(), hall.getZ() + 0.5 + dz, 0.55);
    }

    @Override
    public void stop() {
        noteCompany();
    }
}
