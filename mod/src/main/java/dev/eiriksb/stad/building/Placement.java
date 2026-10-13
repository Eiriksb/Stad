package dev.eiriksb.stad.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A blueprint put somewhere in the world: where each block goes after rotating, in the order a builder works
 * (clear the site from the top down, then build from the bottom up, things that hang on other blocks last in each
 * layer), and what each block costs.
 */
public record Placement(BlockPos origin, Rotation rotation, Vec3i size, List<Step> steps, List<Blueprint.Marker> markers) {
    /** Clear {@code pos} (state is air), or put {@code state} there for {@code count} of {@code item} (count 0: free). */
    public record Step(BlockPos pos, BlockState state, Item item, int count) {
        public boolean clearing() {
            return state.isAir();
        }
    }

    public static Placement of(Blueprint blueprint, BlockPos origin, Rotation rotation) {
        Vec3i size = rotatedSize(blueprint.size(), rotation);
        List<Step> clear = new ArrayList<>();
        List<Step> build = new ArrayList<>();
        for (Blueprint.Entry e : blueprint.entries()) {
            BlockPos pos = origin.offset(rotate(e.pos(), blueprint.size(), rotation));
            BlockState state = e.state().rotate(rotation);
            if (state.isAir()) {
                clear.add(new Step(pos, state, Items.AIR, 0));
            } else if (!(state.getBlock() instanceof LiquidBlock)) {
                Item item = state.getBlock().asItem();
                build.add(new Step(pos, state, item, cost(state, item)));
            }
        }
        clear.sort(Comparator.comparingInt((Step s) -> -s.pos().getY()));
        build.sort(Comparator.comparingInt((Step s) -> s.pos().getY()).thenComparing(s -> hangsOnOthers(s.state())));
        List<Step> steps = new ArrayList<>(clear);
        steps.addAll(build);
        List<Blueprint.Marker> markers = new ArrayList<>();
        for (Blueprint.Marker m : blueprint.markers()) {
            markers.add(new Blueprint.Marker(origin.offset(rotate(m.pos(), blueprint.size(), rotation)), m.name()));
        }
        return new Placement(origin, rotation, size, List.copyOf(steps), List.copyOf(markers));
    }

    /** The rotation that makes a building's front (north in its blueprint) face someone looking {@code facing}. */
    public static Rotation facingViewer(Direction facing) {
        return switch (facing) {
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Vec3i rotatedSize(Vec3i size, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? new Vec3i(size.getZ(), size.getY(), size.getX()) : size;
    }

    /** {@code pos} inside a blueprint of {@code size}, rotated about the vertical axis, still within 0..size-1. */
    public static BlockPos rotate(BlockPos pos, Vec3i size, Rotation rotation) {
        int x = pos.getX(), z = pos.getZ(), sx = size.getX(), sz = size.getZ();
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(sz - 1 - z, pos.getY(), x);
            case CLOCKWISE_180 -> new BlockPos(sx - 1 - x, pos.getY(), sz - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, pos.getY(), sx - 1 - x);
            default -> pos;
        };
    }

    /** Items it takes: the second half of beds and doors comes with the first, double slabs take two. */
    public static int cost(BlockState state, Item item) {
        if (item == Items.AIR) {
            return 0;
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return 0;
        }
        if ((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return 0;
        }
        if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        return 1;
    }

    /** Blocks that need others to stand on or hang from go last in their layer. */
    public static boolean hangsOnOthers(BlockState state) {
        Block b = state.getBlock();
        return b instanceof DoorBlock || b instanceof BedBlock || b instanceof TorchBlock || b instanceof LanternBlock
                || b instanceof CarpetBlock || b instanceof BushBlock || b instanceof LadderBlock || b instanceof ButtonBlock
                || b instanceof LeverBlock || b instanceof SignBlock || b instanceof TrapDoorBlock || b instanceof DoublePlantBlock;
    }

    /** Where the markers called {@code name} are, in the world. */
    public List<BlockPos> markers(String name) {
        return markers.stream().filter(m -> m.name().equals(name)).map(Blueprint.Marker::pos).toList();
    }

    public BoundingBox bounds() {
        return BoundingBox.fromCorners(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }

    /** Everything it takes to build, item by item. */
    public Map<Item, Integer> materials() {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (Step s : steps) {
            if (s.count() > 0) {
                m.merge(s.item(), s.count(), Integer::sum);
            }
        }
        return m;
    }

    /** Where a building goes when someone stands at {@code clicked} looking {@code facing}: centred on it, floor level with it. */
    public static BlockPos originFor(Blueprint blueprint, BlockPos clicked, Rotation rotation) {
        Vec3i size = rotatedSize(blueprint.size(), rotation);
        return clicked.offset(-size.getX() / 2, 0, -size.getZ() / 2);
    }
}
