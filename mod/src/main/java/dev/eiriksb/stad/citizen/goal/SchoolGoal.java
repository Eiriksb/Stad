package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Skills;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Optional;

/**
 * A child goes to school by day while the teacher is there (PLAN.md §6.9). A school day counts once they've been in
 * class for {@link #DAY} ticks with the teacher; each adds to their schooling, which makes them learn any job faster
 * once they're grown.
 */
public class SchoolGoal extends Goal {
    /** ticks in class that make a school day */
    static final int DAY = 2400;

    private final CitizenEntity child;
    private final Movement.Approach approach = new Movement.Approach();
    private BlockPos school;
    private int inClass;
    private int wander;

    public SchoolGoal(CitizenEntity child) {
        this.child = child;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean schoolTime() {
        long t = child.level().getDayTime() % 24000;
        return t >= 1000 && t < 9000;
    }

    /** A school whose teacher is there now. */
    private Optional<BlockPos> openSchool() {
        Colony colony = child.colony().orElse(null);
        if (colony == null || !(child.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        for (CitizenRecord r : colony.citizens()) {
            if (Jobs.TEACHER.equals(r.job) && level.getEntity(r.uuid) instanceof CitizenEntity t && TeacherGoal.teaching(t)) {
                Optional<Building> b = Workplaces.of(colony, r).filter(x -> BuildingDef.SCHOOL.equals(x.type()));
                if (b.isPresent()) {
                    return Optional.of(Workplaces.spot(b.get()));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean canUse() {
        if (!child.isBaby() || !schoolTime() || child.isSleeping() || child.ill() || child.hunger() < 4
                || child.record().map(r -> r.schoolDay == child.level().getDayTime() / 24000).orElse(true)) {
            return false;
        }
        school = openSchool().orElse(null);
        return school != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        approach.reset();
        inClass = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (school == null) {
            return;
        }
        if (child.tickCount % 40 == 0) {
            child.doing(child.blockPosition().closerThan(school, 6) ? "At school" : "Off to school");
        }
        if (child.blockPosition().closerThan(school, 6)) {
            if (++inClass >= DAY) {
                child.record().ifPresent(this::schoolDay);
            }
            if (--wander <= 0) { // fidgeting in their seat
                wander = 80 + child.getRandom().nextInt(80);
                child.getNavigation().moveTo(school.getX() + 0.5 + child.getRandom().nextInt(5) - 2, school.getY(),
                        school.getZ() + 0.5 + child.getRandom().nextInt(5) - 2, 0.5);
            }
        } else {
            approach.tick(child, school, 3.0, 0.6);
        }
    }

    private void schoolDay(CitizenRecord r) {
        r.schoolDay = child.level().getDayTime() / 24000;
        r.schooling = Math.min(Skills.MAX_SCHOOLING, r.schooling + 1);
        child.colony().ifPresent(Colony::changed);
    }

    @Override
    public void stop() {
        child.getNavigation().stop();
    }
}
