package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Illness;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The healer (PLAN.md §6.9) goes round to the town's ill and wounded and treats them: {@link Illness#TREATMENTS}
 * treatments cure an illness, and each one mends a wounded citizen by three hearts. The same patient is treated at most
 * once a minute.
 */
public class HealerGoal extends WorkGoal {
    /** ticks between treatments of one patient */
    static final int SPACING = 1200;
    /** health a treatment gives back */
    static final float MEND = 6;

    private final Map<UUID, Long> treated = new HashMap<>();
    private CitizenEntity patient;
    private int lookIn;

    public HealerGoal(CitizenEntity citizen) {
        super(citizen, Jobs.HEALER);
    }

    private boolean needs(CitizenEntity c, long now) {
        if (!c.isAlive() || now - treated.getOrDefault(c.getUUID(), -SPACING * 2L) < SPACING) {
            return false;
        }
        return c.ill() || c.getHealth() < c.getMaxHealth() * 0.7f;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (patient != null && needs(patient, now)) {
            return true;
        }
        if (--lookIn > 0) {
            return patient != null;
        }
        lookIn = 40;
        patient = null;
        double best = Double.MAX_VALUE;
        for (CitizenRecord r : colony.citizens()) {
            if (level.getEntity(r.uuid) instanceof CitizenEntity c && needs(c, now)) {
                // the ill first, then the nearest
                double d = c.distanceToSqr(citizen) + (c.ill() ? 0 : 1e6);
                if (d < best) {
                    best = d;
                    patient = c;
                }
            }
        }
        return patient != null;
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Nobody is ill or hurt";
    }

    @Override
    protected String doingNow() {
        return "Looking after a patient";
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (patient == null) {
            return;
        }
        if (!approach.tick(citizen, patient.blockPosition(), 2.5, 0.65)) {
            return;
        }
        citizen.getLookControl().setLookAt(patient);
        if (cooldown <= 0) {
            cooldown = pace(60);
        }
        if (--cooldown > 0) {
            if (cooldown % 15 == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getY() + 1, patient.getZ(), 3, 0.3, 0.3, 0.3, 0);
            }
            return;
        }
        treat(level, colony, patient);
        patient = null;
        approach.reset();
    }

    /** One treatment. */
    void treat(ServerLevel level, Colony colony, CitizenEntity c) {
        treated.put(c.getUUID(), level.getGameTime());
        c.record().ifPresent(r -> Illness.treat(level, colony, r));
        if (c.getHealth() < c.getMaxHealth()) {
            c.heal(MEND);
        }
        level.sendParticles(ParticleTypes.HEART, c.getX(), c.getY() + 1.2, c.getZ(), 4, 0.3, 0.2, 0.3, 0);
        level.playSound(null, c.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.6f, 1.2f);
        worked(1);
    }
}
