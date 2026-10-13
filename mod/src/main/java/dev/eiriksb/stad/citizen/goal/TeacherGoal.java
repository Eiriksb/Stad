package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;

import java.util.List;

/**
 * The teacher (PLAN.md §6.9) stands at the school's lectern by day, teaching whichever of the town's children have come
 * ({@link SchoolGoal}).
 */
public class TeacherGoal extends WorkGoal {
    private int lessonIn;

    public TeacherGoal(CitizenEntity citizen) {
        super(citizen, Jobs.TEACHER);
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        return workplace(colony).isPresent(); // a teacher needs a school
    }

    /** Whether a teacher is at their school now (children come while they are). */
    public static boolean teaching(CitizenEntity teacher) {
        return Jobs.TEACHER.equals(teacher.job().orElse(null)) && teacher.canWork()
                && teacher.colony().flatMap(c -> teacher.record().flatMap(r -> Workplaces.of(c, r)))
                .map(b -> teacher.blockPosition().closerThan(Workplaces.spot(b), 5)).orElse(false);
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        return "No school to teach in: build one (it needs a lectern)";
    }

    @Override
    protected String doingNow() {
        return level().getEntitiesOfClass(CitizenEntity.class, citizen.getBoundingBox().inflate(8), CitizenEntity::isBaby).isEmpty()
                ? "Waiting at the school for children" : "Teaching";
    }

    private net.minecraft.world.level.Level level() {
        return citizen.level();
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        BlockPos spot = workplace(colony).map(Workplaces::spot).orElse(null);
        if (spot == null || !approach.tick(citizen, spot, 2.0, 0.6)) {
            return;
        }
        if (--lessonIn > 0) {
            return;
        }
        lessonIn = pace(200);
        List<CitizenEntity> pupils = level.getEntitiesOfClass(CitizenEntity.class, citizen.getBoundingBox().inflate(8), CitizenEntity::isBaby);
        if (!pupils.isEmpty()) {
            citizen.getLookControl().setLookAt(pupils.getFirst());
            citizen.swing(InteractionHand.MAIN_HAND);
            for (CitizenEntity p : pupils) {
                level.sendParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.4, p.getZ(), 6, 0.3, 0.3, 0.3, 0.5);
            }
            worked(pupils.size());
        }
    }
}
