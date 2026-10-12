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
    /** How long (goal ticks) a worker tries to walk somewhere before taking the shortcut. */
    public static final int PATIENCE = 160;

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
                            return;
                        }
                    }
                }
            }
        }
        int top = Math.max(pos.getY() + 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
        citizen.teleportTo(pos.getX() + 0.5, top, pos.getZ() + 0.5);
    }

    public static boolean standable(ServerLevel level, BlockPos at) {
        return level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty();
    }

    /** Walks toward a block, taking the shortcut after {@link #PATIENCE} ticks. Call every goal tick; returns whether it's within reach. */
    public static final class Approach {
        private BlockPos target;
        private int stuck;

        public boolean tick(CitizenEntity citizen, BlockPos pos, double reach, double speed) {
            if (near(citizen, pos, reach)) {
                citizen.getNavigation().stop();
                stuck = 0;
                return true;
            }
            if (!pos.equals(target) || citizen.getNavigation().isDone()) {
                walkNextTo(citizen, pos, speed);
                if (!pos.equals(target)) {
                    stuck = 0;
                }
                target = pos;
            }
            if (++stuck > PATIENCE) {
                teleportNear(citizen, pos);
                stuck = 0;
            }
            return false;
        }

        public void reset() {
            target = null;
            stuck = 0;
        }
    }
}
