package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Set;

/** Getting citizens places: walking up beside things, and a shortcut when they're stuck. */
public final class Movement {
    /**
     * How long (goal ticks) a worker can go without getting any closer before taking the shortcut. It counts from when
     * they last got closer, not from when they set off: a long walk that's going well isn't cut short.
     */
    public static final int PATIENCE = 200;

    private Movement() {}

    /**
     * Walks up beside a block. Not {@code moveTo(pos)}: for a solid target (a bed, a chest) the ground navigation aims
     * for the first open space above it, which under a roof or a tree is on top of the roof.
     */
    public static void walkNextTo(CitizenEntity citizen, BlockPos target, double speed) {
        Path path = citizen.getNavigation().createPath(Set.of(target), 1);
        if (path != null) {
            citizen.getNavigation().moveTo(path, speed);
        }
    }

    public static boolean near(CitizenEntity citizen, BlockPos pos, double distance) {
        return citizen.getEyePosition().distanceTo(pos.getCenter()) <= distance;
    }

    /** Somewhere to stand within reach of {@code pos} (not on it); on top of the column when there's nowhere. */
    public static void teleportNear(CitizenEntity citizen, BlockPos pos) {
        ServerLevel level = (ServerLevel) citizen.level();
        puff(level, citizen);
        for (int r = 1; r <= 4; r++) {
            for (int dy = 1; dy >= -4; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos at = pos.offset(dx, dy, dz);
                        if (standable(level, at) && !at.equals(pos) && !at.above().equals(pos)) {
                            citizen.getNavigation().stop();
                            citizen.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                            puff(level, citizen);
                            return;
                        }
                    }
                }
            }
        }
        int top = Math.max(pos.getY() + 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
        citizen.teleportTo(pos.getX() + 0.5, top, pos.getZ() + 0.5);
        puff(level, citizen);
    }

    /** A little cloud where they were and where they come out, so it doesn't look like a glitch. */
    private static void puff(ServerLevel level, CitizenEntity citizen) {
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, citizen.getX(), citizen.getY() + 0.8, citizen.getZ(), 8, 0.25, 0.4, 0.25, 0.02);
    }

    public static boolean standable(ServerLevel level, BlockPos at) {
        return level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty();
    }

    /**
     * Walks toward a block, taking the shortcut when they've got no closer for {@link #PATIENCE} ticks (sooner when
     * there's no way there at all). Call every goal tick; returns whether it's within reach. A new path is worked out at
     * most every {@link #REPATH} ticks: in a big town, many workers asking for paths every tick to somewhere they can't
     * quite reach was most of the server's time.
     */
    public static final class Approach {
        static final int REPATH = 20;
        private BlockPos target;
        private int stuck;
        private int repath;
        /** the closest they've got so far */
        private double best;

        public boolean tick(CitizenEntity citizen, BlockPos pos, double reach, double speed) {
            if (near(citizen, pos, reach)) {
                citizen.getNavigation().stop();
                stuck = 0;
                return true;
            }
            double distance = citizen.position().distanceTo(pos.getCenter());
            if (!pos.equals(target)) {
                stuck = 0;
                repath = 0;
                target = pos;
                best = distance;
            } else if (distance < best - 1) { // getting there
                best = distance;
                stuck = 0;
            }
            if (--repath <= 0 && citizen.getNavigation().isDone()) {
                repath = REPATH;
                Path path = citizen.getNavigation().createPath(Set.of(pos), 1);
                if (path != null && path.canReach()) {
                    citizen.getNavigation().moveTo(path, speed);
                } else {
                    if (path != null) {
                        citizen.getNavigation().moveTo(path, speed); // as close as it goes
                    }
                    if (path == null || citizen.getNavigation().isDone()) {
                        stuck += REPATH; // no way there (or no closer): the shortcut comes sooner
                    }
                }
            }
            if (++stuck > PATIENCE) {
                teleportNear(citizen, pos);
                stuck = 0;
                repath = 0;
                best = citizen.position().distanceTo(pos.getCenter());
            }
            return false;
        }

        public void reset() {
            target = null;
            stuck = 0;
            repath = 0;
        }
    }
}
