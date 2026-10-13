package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.war.Combat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A guard's day (PLAN.md §6.7): fight whatever threatens the town (monsters on its land, raiders, the enemy in a war
 * raid), fall back to the town hall when badly hurt, and otherwise walk a patrol between watchtowers, barracks and the
 * town hall. Brave citizens fight the same way while their town is attacked.
 */
public class GuardGoal extends Goal {
    private static final double REACH = 2.2;
    private final CitizenEntity citizen;
    private LivingEntity target;
    private int look;
    private int swing;
    private BlockPos patrol;
    private int patrolTicks;
    private final Movement.Approach approach = new Movement.Approach();

    public GuardGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean patrols() {
        return Combat.guard(citizen) && (citizen.canWork() || leader() != null);
    }

    /** The player a marching guard follows in a raid. */
    private net.minecraft.server.level.ServerPlayer leader() {
        return dev.eiriksb.stad.war.Wars.leaderOf(citizen.getUUID())
                .map(id -> ((ServerLevel) citizen.level()).getServer().getPlayerList().getPlayer(id)).orElse(null);
    }

    @Override
    public boolean canUse() {
        if (citizen.isSleeping() || !(citizen.level() instanceof ServerLevel) || !Combat.fighter(citizen) || citizen.colony().isEmpty()) {
            return false;
        }
        if (citizen.tickCount % 10 == 0) {
            target = Combat.target(citizen);
        }
        return target != null || patrols();
    }

    @Override
    public boolean canContinueToUse() {
        return !citizen.isSleeping() && Combat.fighter(citizen) && (target != null && target.isAlive() || patrols());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        approach.reset();
        patrol = null;
        citizen.colony().ifPresent(c -> Combat.arm(citizen, (ServerLevel) citizen.level(), c));
    }

    @Override
    public void stop() {
        target = null;
        citizen.getNavigation().stop();
    }

    @Override
    public void tick() {
        Colony colony = citizen.colony().orElse(null);
        if (colony == null) {
            return;
        }
        if (++look % 10 == 0) {
            LivingEntity nearer = Combat.target(citizen);
            if (nearer != null) {
                target = nearer;
            }
            if (look % 200 == 0) {
                Combat.arm(citizen, (ServerLevel) citizen.level(), colony);
            }
        }
        if (target != null && !target.isAlive()) {
            target = null;
        }
        if (look % 40 == 0 && dev.eiriksb.stad.colony.Jobs.GUARD.equals(citizen.job().orElse(null))) {
            if (target != null) {
                citizen.doing("Fighting " + target.getName().getString());
            } else if (Combat.unarmed(citizen)) {
                citizen.stuck("Patrolling with no sword: put a sword (and armour) in the town hall");
            } else {
                citizen.doing("Patrolling");
            }
        }
        if (target != null) {
            fight(colony);
        } else if (leader() != null) {
            net.minecraft.server.level.ServerPlayer p = leader();
            if (p.level() == citizen.level() && citizen.distanceToSqr(p) > 16 && look % 10 == 0) {
                citizen.getNavigation().moveTo(p, 0.8);
            }
        } else if (patrols()) {
            patrol(colony);
        }
    }

    private void fight(Colony colony) {
        if (citizen.getHealth() < 6) { // badly hurt: fall back, away from the raiders (who head for the town hall)
            approach.tick(citizen, ShelterGoal.away(citizen, colony), 3, 0.9);
            return;
        }
        citizen.getLookControl().setLookAt(target, 30, 30);
        double reach = REACH + target.getBbWidth() / 2;
        if (citizen.distanceToSqr(target) > reach * reach) {
            if (look % 10 == 0 || citizen.getNavigation().isDone() && look % 3 == 0) {
                citizen.getNavigation().moveTo(target, 0.8);
            }
        } else {
            citizen.getNavigation().stop();
        }
        if (--swing <= 0 && citizen.distanceToSqr(target) <= reach * reach && citizen.hasLineOfSight(target)) {
            citizen.swing(InteractionHand.MAIN_HAND);
            citizen.doHurtTarget(target);
            swing = 20;
        }
    }

    private void patrol(Colony colony) {
        if (patrol == null || --patrolTicks <= 0) {
            List<BlockPos> points = new ArrayList<>();
            points.add(colony.center);
            for (Building b : colony.buildings()) {
                if (b.done() && b.type() != null && (b.type().getPath().equals("watchtower") || b.type().getPath().equals("barracks"))) {
                    b.placement().ifPresent(p -> points.add(p.bounds().getCenter().atY(p.bounds().minY() + 1)));
                }
            }
            if (points.size() == 1) { // no towers: walk round the town's edge
                int r = Math.max(8, colony.radius - 10);
                double a = citizen.getRandom().nextDouble() * Math.PI * 2;
                BlockPos edge = colony.center.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
                points.add(((ServerLevel) citizen.level()).getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, edge));
            }
            patrol = points.get(citizen.getRandom().nextInt(points.size()));
            patrolTicks = 400 + citizen.getRandom().nextInt(400);
            approach.reset();
        }
        if (approach.tick(citizen, patrol, 3, 0.55)) {
            citizen.getLookControl().setLookAt(patrol.getX() + citizen.getRandom().nextInt(17) - 8, patrol.getY() + 1,
                    patrol.getZ() + citizen.getRandom().nextInt(17) - 8);
        }
    }
}
