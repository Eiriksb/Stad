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
 * Where the miners dig: a mine's shaft (a 5x5 pit under the mine building's {@code stad:shaft} marker) or a quarry
 * (an open pit of any size, its corners marked {@code stad:quarry_<depth>}, PLAN.md §6.11), dug a layer at a time. One
 * block of each layer stays as a step, each a block further round the edge than the one above, so the steps spiral
 * down and a miner can walk to the bottom and back without ladders (with three blocks of headroom over every step). A
 * torch goes on the wall every five layers, and water or lava breaking in is walled off with cobblestone.
 * <p>
 * A shaft goes down to bedrock, and at the heights where the ores are ({@link #LEVELS}) the miner strip-mines before
 * going deeper: tunnels two high out from the shaft each way, and from the east and west ones side tunnels every
 * {@link #SPACING} blocks, so every block between them is seen; lit with torches, on the town's land only. Ore they
 * come across is dug out after, the whole vein ({@code MineGoal}).
 */
public final class Mine {
    public static final int HALF = 2;
    /** a quarry's depth that means "down to bedrock" */
    public static final int BEDROCK = 0;
    /** the heights a shaft is strip-mined at, top down: copper and coal; iron; lapis; gold; and diamonds and redstone */
    public static final int[] LEVELS = {48, 16, 0, -16, -32, -58};
    /** how far the tunnels go out from the shaft, and the side tunnels from them */
    public static final int CORRIDOR = 24, BRANCH = 12;
    /** blocks from one side tunnel to the next (two of stone between) */
    public static final int SPACING = 3;
    /** a torch every this many blocks along a tunnel */
    static final int TORCH = 6;

    private Mine() {}

    /**
     * A pit: its corners (inclusive), the height of its top layer, how many layers it goes down (to bedrock when it's
     * deeper than the world), and whether it's strip-mined at the ore levels.
     */
    public record Pit(int x0, int z0, int x1, int z1, int top, int depth, boolean tunnels) {
        public Pit(int x0, int z0, int x1, int z1, int top, int depth) {
            this(x0, z0, x1, z1, top, depth, false);
        }

        /** A mine's 5x5 shaft whose top layer's middle is {@code top}. */
        public static Pit shaft(BlockPos top) {
            return new Pit(top.getX() - HALF, top.getZ() - HALF, top.getX() + HALF, top.getZ() + HALF, top.getY(), StadConfig.MINE_DEPTH.get(), true);
        }

        /** The edge, clockwise from the north-west corner: the steps follow it down. */
        public List<int[]> rim() {
            List<int[]> r = new ArrayList<>();
            for (int x = x0; x <= x1; x++) {
                r.add(new int[]{x, z0});
            }
            for (int z = z0 + 1; z <= z1; z++) {
                r.add(new int[]{x1, z});
            }
            for (int x = x1 - 1; x >= x0; x--) {
                r.add(new int[]{x, z1});
            }
            for (int z = z1 - 1; z > z0; z--) {
                r.add(new int[]{x0, z});
            }
            return r;
        }

        /** Where layer {@code d}'s step is. */
        public int[] step(int d) {
            List<int[]> r = rim();
            return r.get(Math.floorMod(d, r.size()));
        }

        public BlockPos middle() {
            return new BlockPos((x0 + x1) / 2, top, (z0 + z1) / 2);
        }
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

    /** Every finished mine's shaft and quarry of the town. */
    public static List<Pit> pits(Colony colony) {
        List<Pit> out = new ArrayList<>();
        shafts(colony).forEach(s -> out.add(Pit.shaft(s)));
        for (Building b : colony.buildings()) {
            if (!b.done() || !b.is(BuildingDef.QUARRY)) {
                continue;
            }
            b.placement().ifPresent(p -> {
                List<dev.eiriksb.stad.building.Blueprint.Marker> corners = p.markers().stream().filter(m -> m.name().startsWith("stad:quarry")).toList();
                if (corners.isEmpty()) {
                    return;
                }
                int depth = 16;
                try {
                    depth = Integer.parseInt(corners.getFirst().name().substring("stad:quarry_".length()));
                } catch (RuntimeException ignored) {
                }
                int x0 = corners.stream().mapToInt(m -> m.pos().getX()).min().orElseThrow(), x1 = corners.stream().mapToInt(m -> m.pos().getX()).max().orElseThrow();
                int z0 = corners.stream().mapToInt(m -> m.pos().getZ()).min().orElseThrow(), z1 = corners.stream().mapToInt(m -> m.pos().getZ()).max().orElseThrow();
                out.add(new Pit(x0, z0, x1, z1, corners.getFirst().pos().getY(), depth == BEDROCK ? 4096 : depth));
            });
        }
        return out;
    }

    /** Where layer {@code depth}'s step is (relative to the middle) in a mine's shaft. */
    public static int[] step(int depth) {
        int[] s = Pit.shaft(BlockPos.ZERO).step(depth);
        return new int[]{s[0], s[1]};
    }

    /** Something to do in a pit: dig a block out, or ({@code step}) put a step where there's none (a pit on a slope). */
    public record Task(BlockPos pos, boolean step) {}

    /** The next thing to do in a shaft on its own, without its tunnels. */
    public static Optional<Task> task(ServerLevel level, BlockPos top, BlockPos near) {
        return task(level, null, new Pit(top.getX() - HALF, top.getZ() - HALF, top.getX() + HALF, top.getZ() + HALF, top.getY(),
                StadConfig.MINE_DEPTH.get()), near);
    }

    /**
     * What to do next in this pit, top layer first: a missing step (where it starts above the ground), else the block
     * nearest {@code near} in the top unfinished layer; and in a shaft, at an ore level, its tunnels before it goes any
     * deeper. Empty when it's done.
     */
    public static Optional<Task> task(ServerLevel level, Colony colony, Pit pit, BlockPos near) {
        for (int d = 0; d < pit.depth(); d++) {
            int y = pit.top() - d;
            if (y <= level.getMinBuildHeight()) {
                return Optional.empty();
            }
            int[] keep = pit.step(d);
            BlockPos stepAt = new BlockPos(keep[0], y, keep[1]);
            if (level.getBlockState(stepAt).canBeReplaced()) {
                return Optional.of(new Task(stepAt, true));
            }
            Optional<BlockPos> dig = layer(level, pit, d, near);
            if (dig.isPresent()) {
                return dig.map(p -> new Task(p, false));
            }
            if (pit.tunnels() && colony != null && level(y)) {
                Optional<Task> t = tunnels(level, colony, pit, y);
                if (t.isPresent()) {
                    return t;
                }
            }
        }
        return Optional.empty();
    }

    /** Whether a shaft is strip-mined at this height. */
    public static boolean level(int y) {
        for (int l : LEVELS) {
            if (l == y) {
                return true;
            }
        }
        return false;
    }

    /**
     * A shaft's tunnels at a height, in the order they're dug: each a run of columns out from where it starts, and the
     * side of it its torches go on. East and west, each with its side tunnels north and south; then north and south.
     */
    public static List<Tunnel> tunnels(Pit pit) {
        int cx = (pit.x0() + pit.x1()) / 2, cz = (pit.z0() + pit.z1()) / 2, hx = (pit.x1() - pit.x0()) / 2, hz = (pit.z1() - pit.z0()) / 2;
        List<Tunnel> out = new ArrayList<>();
        for (int sign : new int[]{1, -1}) {
            out.add(new Tunnel(cx + sign * (hx + 1), cz, Direction.fromAxisAndDirection(Direction.Axis.X, sign > 0 ? Direction.AxisDirection.POSITIVE
                    : Direction.AxisDirection.NEGATIVE), CORRIDOR, Direction.NORTH));
            for (int i = SPACING - 1; i < CORRIDOR; i += SPACING) {
                int x = cx + sign * (hx + 1 + i);
                out.add(new Tunnel(x, cz - 1, Direction.NORTH, BRANCH, Direction.EAST));
                out.add(new Tunnel(x, cz + 1, Direction.SOUTH, BRANCH, Direction.EAST));
            }
        }
        out.add(new Tunnel(cx, cz - hz - 1, Direction.NORTH, CORRIDOR, Direction.EAST));
        out.add(new Tunnel(cx, cz + hz + 1, Direction.SOUTH, CORRIDOR, Direction.EAST));
        return out;
    }

    /** A tunnel: where it starts, the way it goes and how far, and the wall its torches hang on. */
    public record Tunnel(int x, int z, Direction way, int length, Direction wall) {
        public BlockPos at(int i, int y) {
            return new BlockPos(x + way.getStepX() * i, y, z + way.getStepZ() * i);
        }
    }

    /**
     * The next block of a level's tunnels: a floor where it's open below (a cave), else the head-high block, then the
     * one at the feet, of the first column not dug, tunnel by tunnel. A tunnel stops at the edge of the town's land.
     */
    public static Optional<Task> tunnels(ServerLevel level, Colony colony, Pit pit, int y) {
        for (Tunnel t : tunnels(pit)) {
            for (int i = 0; i < t.length(); i++) {
                BlockPos feet = t.at(i, y);
                if (!colony.contains(level.dimension(), feet)) {
                    break;
                }
                if (level.getBlockState(feet.below()).canBeReplaced()) {
                    return Optional.of(new Task(feet.below(), true));
                }
                if (diggable(level, feet.above())) {
                    return Optional.of(new Task(feet.above(), false));
                }
                if (diggable(level, feet)) {
                    return Optional.of(new Task(feet, false));
                }
                if (i % TORCH == TORCH / 2) {
                    torch(level, feet.above(), t.wall());
                }
            }
        }
        return Optional.empty();
    }

    /** A torch on the wall on {@code side} of an open block, if there's a wall there to hang it on. */
    static void torch(ServerLevel level, BlockPos at, Direction side) {
        BlockPos wall = at.relative(side);
        if (level.getBlockState(at).isAir() && level.getBlockState(wall).isFaceSturdy(level, wall, side.getOpposite())) {
            level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, side.getOpposite()), 3);
        }
    }

    /** Whether a block is ore (worth following round the vein). */
    public static boolean ore(BlockState s) {
        return s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }

    private static List<BlockPos> left(ServerLevel level, Pit pit, int d) {
        int y = pit.top() - d;
        int[] keep = pit.step(d);
        List<BlockPos> left = new ArrayList<>();
        for (int x = pit.x0(); x <= pit.x1(); x++) {
            for (int z = pit.z0(); z <= pit.z1(); z++) {
                if (x == keep[0] && z == keep[1]) {
                    continue;
                }
                BlockPos p = new BlockPos(x, y, z);
                if (diggable(level, p)) {
                    left.add(p);
                }
            }
        }
        return left;
    }

    private static Optional<BlockPos> layer(ServerLevel level, Pit pit, int d, BlockPos near) {
        List<BlockPos> left = left(level, pit, d);
        if (left.isEmpty() && d % 5 == 4) {
            light(level, pit, d);
        }
        return left.stream().min(Comparator.comparingDouble(p -> p.distSqr(near)));
    }

    /** The next block to dig in this shaft (the nearest to {@code near} in the top unfinished layer), if it isn't done. */
    public static Optional<BlockPos> next(ServerLevel level, BlockPos top, BlockPos near) {
        Pit pit = Pit.shaft(top);
        for (int d = 0; d < pit.depth(); d++) {
            if (pit.top() - d <= level.getMinBuildHeight()) {
                return Optional.empty();
            }
            Optional<BlockPos> dig = layer(level, pit, d, near);
            if (dig.isPresent()) {
                return dig;
            }
        }
        return Optional.empty();
    }

    /** How many layers of a mine's shaft are dug. */
    public static int depth(ServerLevel level, BlockPos top) {
        return depth(level, Pit.shaft(top));
    }

    public static int depth(ServerLevel level, Pit pit) {
        for (int d = 0; d < pit.depth(); d++) {
            if (!left(level, pit, d).isEmpty()) {
                return d;
            }
        }
        return pit.depth();
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

    /** A torch on the pit's north or south wall, at the height of layer {@code depth}, on the side away from that layer's step. */
    static void light(ServerLevel level, Pit pit, int depth) {
        int[] keep = pit.step(depth);
        boolean north = keep[1] != pit.z0();
        int x = (pit.x0() + pit.x1()) / 2;
        BlockPos at = new BlockPos(x, pit.top() - depth, north ? pit.z0() : pit.z1());
        BlockPos wall = at.relative(north ? Direction.NORTH : Direction.SOUTH);
        if (level.getBlockState(at).isAir() && level.getBlockState(wall).isFaceSturdy(level, wall, north ? Direction.SOUTH : Direction.NORTH)) {
            level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, north ? Direction.SOUTH : Direction.NORTH), 3);
        }
    }
}
