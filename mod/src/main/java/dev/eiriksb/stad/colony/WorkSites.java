package dev.eiriksb.stad.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where on a town's land there's work: farmland for farmers, trees for lumberjacks. Found by scanning the loaded part
 * of the land, at most every thirty seconds per town. Server thread.
 */
public final class WorkSites {
    private static final long REFRESH = 600;
    private record Scan(long time, List<BlockPos> farmland, List<BlockPos> trees, List<BlockPos> cooking, List<BlockPos> shore) {}
    private static final Map<Integer, Scan> scans = new HashMap<>();

    private WorkSites() {}

    public static List<BlockPos> farmland(ServerLevel level, Colony colony) {
        return scan(level, colony).farmland;
    }

    /** The bottom log of each natural tree (logs with leaves that grew there, not a player's log wall). */
    public static List<BlockPos> trees(ServerLevel level, Colony colony) {
        return scan(level, colony).trees;
    }

    /** Furnaces, smokers and campfires on the town's land, where a cook can work. */
    public static List<BlockPos> cooking(ServerLevel level, Colony colony) {
        return scan(level, colony).cooking;
    }

    /**
     * Where a fisher can stand: open ground beside a pond or a river (at least eight blocks of water close by), the
     * water's surface open to the sky. Each spot is the block they stand on; {@link #waterBeside} finds the water.
     */
    public static List<BlockPos> shore(ServerLevel level, Colony colony) {
        return scan(level, colony).shore;
    }

    /** The water next to a shore spot (where the line goes), if it's still there. */
    public static java.util.Optional<BlockPos> waterBeside(ServerLevel level, BlockPos stand) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos w = stand.relative(d).below();
            if (level.getFluidState(w).is(net.minecraft.tags.FluidTags.WATER) && level.getFluidState(w).isSource()) {
                return java.util.Optional.of(w);
            }
        }
        return java.util.Optional.empty();
    }

    /** Forget the last scan (a tree was felled). */
    public static void invalidate(Colony colony) {
        scans.remove(colony.id);
    }

    private static Scan scan(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        Scan s = scans.get(colony.id);
        if (s != null && now - s.time < REFRESH && now >= s.time) {
            return s;
        }
        List<BlockPos> farmland = new ArrayList<>();
        List<BlockPos> trees = new ArrayList<>();
        List<BlockPos> cooking = new ArrayList<>();
        java.util.Set<BlockPos> shore = new java.util.LinkedHashSet<>();
        int r = colony.radius;
        BlockPos c = colony.center;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r || !level.isLoaded(p.set(c.getX() + dx, c.getY(), c.getZ() + dz))) {
                    continue;
                }
                for (int y = c.getY() - 10; y <= c.getY() + 12; y++) {
                    p.set(c.getX() + dx, y, c.getZ() + dz);
                    BlockState state = level.getBlockState(p);
                    if (state.is(Blocks.FARMLAND)) {
                        farmland.add(p.immutable());
                    } else if (state.is(Blocks.FURNACE) || state.is(Blocks.SMOKER) || state.is(BlockTags.CAMPFIRES)) {
                        cooking.add(p.immutable());
                    } else if (shore.size() < 48 && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && state.getFluidState().isSource()
                            && level.getBlockState(p.above()).isAir() && (dx + dz) % 3 == 0) {
                        standBeside(level, p.immutable()).ifPresent(shore::add);
                    } else if (state.is(BlockTags.LOGS) && level.getBlockState(p.below()).is(BlockTags.DIRT) && naturalTree(level, p.immutable())
                            && !insideBuilding(colony, p)) {
                        trees.add(p.immutable());
                    }
                }
            }
        }
        s = new Scan(now, List.copyOf(farmland), List.copyOf(trees), List.copyOf(cooking), List.copyOf(shore));
        scans.put(colony.id, s);
        return s;
    }

    /** Dry ground to stand on beside this water, when it's a real pond (eight or more water blocks close by). */
    private static java.util.Optional<BlockPos> standBeside(ServerLevel level, BlockPos water) {
        int wet = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (level.getFluidState(water.offset(dx, 0, dz)).is(net.minecraft.tags.FluidTags.WATER)) {
                    wet++;
                }
            }
        }
        if (wet < 8) {
            return java.util.Optional.empty();
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos ground = water.relative(d);
            if (level.getBlockState(ground).isFaceSturdy(level, ground, net.minecraft.core.Direction.UP) // a dusting of snow is fine
                    && level.getBlockState(ground.above()).getCollisionShape(level, ground.above()).isEmpty()
                    && level.getBlockState(ground.above(2)).getCollisionShape(level, ground.above(2)).isEmpty()) {
                return java.util.Optional.of(ground.above());
            }
        }
        return java.util.Optional.empty();
    }

    /** A trunk going up into leaves that grew naturally (not placed by a player). */
    static boolean naturalTree(ServerLevel level, BlockPos base) {
        BlockPos.MutableBlockPos p = base.mutable();
        for (int up = 0; up < 24 && level.getBlockState(p).is(BlockTags.LOGS); up++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockState s = level.getBlockState(p.offset(dx, dy, dz));
                        if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) {
                            return true;
                        }
                    }
                }
            }
            p.move(0, 1, 0);
        }
        return false;
    }

    private static boolean insideBuilding(Colony colony, BlockPos pos) {
        for (Building b : colony.buildings()) {
            if (!dev.eiriksb.stad.building.Groves.is(b) && b.placement().map(pl -> pl.bounds().isInside(pos)).orElse(false)) {
                return true; // a grove's trees are there to be felled
            }
        }
        return false;
    }

    /** Every log of the tree standing on {@code base} (connected, diagonals too), at most {@code max}. */
    public static List<BlockPos> treeLogs(ServerLevel level, BlockPos base, int max) {
        List<BlockPos> logs = new ArrayList<>();
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(List.of(base));
        java.util.Set<BlockPos> seen = new java.util.HashSet<>(List.of(base));
        while (!todo.isEmpty() && logs.size() < max) {
            BlockPos at = todo.poll();
            if (!level.getBlockState(at).is(BlockTags.LOGS)) {
                continue;
            }
            logs.add(at);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = at.offset(dx, dy, dz);
                        if (n.getY() >= base.getY() && seen.add(n)) {
                            todo.add(n);
                        }
                    }
                }
            }
        }
        return logs;
    }
}
