package dev.eiriksb.stad.building;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A mine's shaft: a 5x5 pit under the mine building's {@code stad:shaft} marker, dug a layer at a time. One block of each
 * layer stays as a step, each a block further round the wall than the one above, so the steps spiral down and a miner
 * can walk to the bottom and back without ladders (with three blocks of headroom over every step). A torch goes on the
 * wall every five layers, and water or lava breaking in is walled off with cobblestone.
 */
public final class Mine {
    public static final int HALF = 2;
    /** The shaft's edge, clockwise: the steps follow it down. */
    static final List<int[]> RIM = rim();

    private Mine() {}

    private static List<int[]> rim() {
        List<int[]> r = new ArrayList<>();
        for (int x = -HALF; x <= HALF; x++) {
            r.add(new int[]{x, -HALF});
        }
        for (int z = -HALF + 1; z <= HALF; z++) {
            r.add(new int[]{HALF, z});
        }
        for (int x = HALF - 1; x >= -HALF; x--) {
            r.add(new int[]{x, HALF});
        }
        for (int z = HALF - 1; z > -HALF; z--) {
            r.add(new int[]{-HALF, z});
        }
        return List.copyOf(r);
    }

    /** The middle of the shaft's first layer (the ground under the marker), for each finished mine of the town. */
    public static List<BlockPos> shafts(Colony colony) {
        List<BlockPos> out = new ArrayList<>();
        for (Building b : colony.buildings()) {
            if (b.done() && b.is(BuildingDef.MINE)) {
                b.placement().ifPresent(p -> p.markers("stad:shaft").forEach(m -> out.add(m.below())));
            }
        }
        return out;
    }

    /** Where layer {@code depth}'s step is (relative to the middle). */
    public static int[] step(int depth) {
        return RIM.get(Math.floorMod(depth, RIM.size()));
    }

    /** Something to do in a shaft: dig a block out, or ({@code step}) put a step where there's none (a mine on a slope). */
    public record Task(BlockPos pos, boolean step) {}

    /**
     * What to do next in this shaft, top layer first: a missing step (where the shaft starts above the ground), else the
     * block nearest {@code near} in the top unfinished layer. Empty when it's done.
     */
    public static Optional<Task> task(ServerLevel level, BlockPos top, BlockPos near) {
        int max = StadConfig.MINE_DEPTH.get();
        for (int d = 0; d < max; d++) {
            int y = top.getY() - d;
            if (y <= level.getMinBuildHeight()) {
                return Optional.empty();
            }
            int[] keep = step(d);
            BlockPos stepAt = new BlockPos(top.getX() + keep[0], y, top.getZ() + keep[1]);
            if (level.getBlockState(stepAt).canBeReplaced()) {
                return Optional.of(new Task(stepAt, true));
            }
            Optional<BlockPos> dig = layer(level, top, d, near);
            if (dig.isPresent()) {
                return dig.map(p -> new Task(p, false));
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> layer(ServerLevel level, BlockPos top, int d, BlockPos near) {
        int y = top.getY() - d;
        int[] keep = step(d);
        List<BlockPos> left = new ArrayList<>();
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                if (dx == keep[0] && dz == keep[1]) {
                    continue;
                }
                BlockPos p = new BlockPos(top.getX() + dx, y, top.getZ() + dz);
                if (diggable(level, p)) {
                    left.add(p);
                }
            }
        }
        if (left.isEmpty() && d % 5 == 4) {
            light(level, top, d);
        }
        return left.stream().min(Comparator.comparingDouble(p -> p.distSqr(near)));
    }

    /** The next block to dig in this shaft (the nearest to {@code near} in the top unfinished layer), if it isn't done. */
    public static Optional<BlockPos> next(ServerLevel level, BlockPos top, BlockPos near) {
        int max = StadConfig.MINE_DEPTH.get();
        for (int d = 0; d < max; d++) {
            int y = top.getY() - d;
            if (y <= level.getMinBuildHeight()) {
                return Optional.empty();
            }
            int[] keep = step(d);
            List<BlockPos> left = new ArrayList<>();
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    if (dx == keep[0] && dz == keep[1]) {
                        continue;
                    }
                    BlockPos p = new BlockPos(top.getX() + dx, y, top.getZ() + dz);
                    if (diggable(level, p)) {
                        left.add(p);
                    }
                }
            }
            if (!left.isEmpty()) {
                return left.stream().min(Comparator.comparingDouble(p -> p.distSqr(near)));
            }
            if (d % 5 == 4) {
                light(level, top, d);
            }
        }
        return Optional.empty();
    }

    /** How many layers are dug. */
    public static int depth(ServerLevel level, BlockPos top) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int max = StadConfig.MINE_DEPTH.get();
        for (int d = 0; d < max; d++) {
            int[] keep = step(d);
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    if ((dx != keep[0] || dz != keep[1]) && diggable(level, p.set(top.getX() + dx, top.getY() - d, top.getZ() + dz))) {
                        return d;
                    }
                }
            }
        }
        return max;
    }

    static boolean diggable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.is(Blocks.WALL_TORCH) && s.getDestroySpeed(level, p) >= 0;
    }

    /** Before digging {@code pos}: water and lava beside it are walled off, and any in it is cleared. */
    public static void seal(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getFluidState(n).isEmpty()) {
                level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }

    /** A torch on the shaft's wall, at the height of layer {@code depth}, on the side away from that layer's step. */
    static void light(ServerLevel level, BlockPos top, int depth) {
        int[] keep = step(depth);
        boolean north = keep[1] != -HALF;
        BlockPos at = new BlockPos(top.getX(), top.getY() - depth, top.getZ() + (north ? -HALF : HALF));
        BlockPos wall = at.relative(north ? Direction.NORTH : Direction.SOUTH);
        if (level.getBlockState(at).isAir() && level.getBlockState(wall).isFaceSturdy(level, wall, north ? Direction.SOUTH : Direction.NORTH)) {
            level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, north ? Direction.SOUTH : Direction.NORTH), 3);
        }
    }
}
