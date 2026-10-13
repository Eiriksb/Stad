package dev.eiriksb.stad.client.architect;

import dev.eiriksb.stad.Stad;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * How a block goes down in the editor: which way it faces (from the face clicked and the way the camera looks, like a
 * player placing it), both halves of beds and doors, and how fences, panes, walls and stairs join their neighbours,
 * worked out on the design's own blocks (never the real world).
 */
final class Placing {
    private Placing() {}

    record Piece(BlockPos offset, BlockState state) {}

    /** The pieces a block makes placed against {@code face}, the camera looking {@code look}, turned {@code turns} quarters more. */
    static List<Piece> place(Block block, Direction face, Direction look, double fracY, int turns) {
        if (face.getAxis().isHorizontal()) {
            if (block == Blocks.TORCH) {
                block = Blocks.WALL_TORCH;
            } else if (block == Blocks.SOUL_TORCH) {
                block = Blocks.SOUL_WALL_TORCH;
            } else if (block == Blocks.REDSTONE_TORCH) {
                block = Blocks.REDSTONE_WALL_TORCH;
            }
        }
        BlockState s = block.defaultBlockState();
        boolean top = face == Direction.DOWN || face.getAxis().isHorizontal() && fracY > 0.5;
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction f;
            if (block instanceof WallTorchBlock || block instanceof LadderBlock || block instanceof WallSignBlock || block instanceof WallBannerBlock) {
                f = face.getAxis().isHorizontal() ? face : look.getOpposite();
            } else if (block instanceof StairBlock || block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof BedBlock) {
                f = look;
            } else {
                f = look.getOpposite();
            }
            s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, f);
        }
        if (s.hasProperty(BlockStateProperties.FACING)) {
            s = s.setValue(BlockStateProperties.FACING, face);
        }
        if (s.hasProperty(BlockStateProperties.AXIS)) {
            s = s.setValue(BlockStateProperties.AXIS, face.getAxis());
        }
        if (block instanceof SlabBlock) {
            s = s.setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
        }
        if ((block instanceof StairBlock || block instanceof TrapDoorBlock) && s.hasProperty(BlockStateProperties.HALF)) {
            s = s.setValue(BlockStateProperties.HALF, top ? Half.TOP : Half.BOTTOM);
        }
        if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            s = s.setValue(BlockStateProperties.ATTACH_FACE, face == Direction.UP ? AttachFace.FLOOR : face == Direction.DOWN ? AttachFace.CEILING : AttachFace.WALL);
            if (face.getAxis().isHorizontal()) {
                s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, face);
            }
        }
        if (s.hasProperty(BlockStateProperties.HANGING)) {
            s = s.setValue(BlockStateProperties.HANGING, face == Direction.DOWN);
        }
        if (s.hasProperty(BlockStateProperties.ROTATION_16)) {
            s = s.setValue(BlockStateProperties.ROTATION_16, Math.floorMod(Math.round((look.toYRot() + 180) / 22.5f), 16));
        }
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
            s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        s = s.rotate(Rotation.values()[Math.floorMod(turns, 4)]);
        List<Piece> out = new ArrayList<>();
        if (block instanceof BedBlock) {
            Direction f = s.getValue(BedBlock.FACING);
            out.add(new Piece(BlockPos.ZERO, s.setValue(BedBlock.PART, BedPart.FOOT)));
            out.add(new Piece(BlockPos.ZERO.relative(f), s.setValue(BedBlock.PART, BedPart.HEAD)));
        } else if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && (block instanceof DoorBlock || block instanceof DoublePlantBlock)) {
            out.add(new Piece(BlockPos.ZERO, s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)));
            out.add(new Piece(BlockPos.ZERO.above(), s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        } else {
            out.add(new Piece(BlockPos.ZERO, s));
        }
        return out;
    }

    /** The other half of a bed, door or tall plant at {@code pos}, if it's one. */
    static Optional<BlockPos> otherHalf(BlockState s, BlockPos pos) {
        if (s.getBlock() instanceof BedBlock) {
            Direction f = s.getValue(BedBlock.FACING);
            return Optional.of(s.getValue(BedBlock.PART) == BedPart.FOOT ? pos.relative(f) : pos.relative(f.getOpposite()));
        }
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return Optional.of(s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below());
        }
        return Optional.empty();
    }

    /** {@code to}'s block with the properties it shares with {@code from} (for replacing stairs with stairs). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState like(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (Property p : from.getProperties()) {
            if (s.hasProperty(p)) {
                s = s.setValue(p, from.getValue(p));
            }
        }
        return s;
    }

    static BlockState mirror(BlockState s, boolean x, boolean z) {
        if (x) {
            s = s.mirror(Mirror.FRONT_BACK);
        }
        if (z) {
            s = s.mirror(Mirror.LEFT_RIGHT);
        }
        return s;
    }

    private static boolean warned;

    /**
     * Lets the changed blocks and their neighbours join up (fences, panes, walls, stairs...): the states they'd take
     * next to each other. Adds any neighbours that change; nothing that was there turns to air.
     */
    static void connect(DesignModel model, Map<Integer, BlockState> changes) {
        Map<Integer, BlockState> work = new LinkedHashMap<>(changes);
        Function<BlockPos, BlockState> blocks = p -> {
            if (model.inSite(p.getX(), p.getY(), p.getZ())) {
                BlockState w = work.get(DesignModel.key(p.getX(), p.getY(), p.getZ()));
                if (w != null) {
                    return w;
                }
            }
            return model.at(p.getX(), p.getY(), p.getZ());
        };
        LevelAccessor level;
        try {
            level = proxy(blocks);
        } catch (RuntimeException e) {
            return;
        }
        List<BlockPos> todo = new ArrayList<>();
        for (int key : changes.keySet()) {
            BlockPos g = DesignModel.grid(key);
            todo.add(g);
            for (Direction d : Direction.values()) {
                todo.add(g.relative(d));
            }
        }
        for (BlockPos g : todo) {
            if (!model.inSite(g.getX(), g.getY(), g.getZ())) {
                continue;
            }
            BlockState before = blocks.apply(g);
            if (before.isAir()) {
                continue;
            }
            BlockState s = before;
            try {
                for (Direction d : Direction.values()) {
                    BlockPos n = g.relative(d);
                    BlockState next = s.updateShape(d, blocks.apply(n), level, g, n);
                    if (!next.isAir()) {
                        s = next;
                    }
                }
            } catch (RuntimeException e) {
                if (!warned) {
                    warned = true;
                    Stad.LOGGER.warn("Couldn't join up blocks in the architect's table", e);
                }
                continue;
            }
            if (!s.equals(before)) {
                work.put(DesignModel.key(g.getX(), g.getY(), g.getZ()), s);
            }
        }
        changes.putAll(work);
    }

    /** A stand-in world for {@code updateShape}: blocks from the design, and nothing that schedules or stores anything. */
    private static LevelAccessor proxy(Function<BlockPos, BlockState> blocks) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource random = RandomSource.create();
        InvocationHandler h = (self, m, args) -> {
            switch (m.getName()) {
                case "getBlockState":
                    return blocks.apply((BlockPos) args[0]);
                case "getFluidState":
                    return blocks.apply((BlockPos) args[0]).getFluidState();
                case "getBlockEntity":
                    return m.getReturnType() == Optional.class ? Optional.empty() : null;
                case "isClientSide":
                    return true;
                case "scheduleTick":
                case "blockUpdated":
                case "updateNeighborsAt":
                case "neighborShapeChanged":
                    return null;
                case "getMinBuildHeight":
                    return -4096;
                case "getHeight":
                    return 8192;
                case "getRandom":
                    return random;
                case "registryAccess":
                    return mc.level.registryAccess();
                case "enabledFeatures":
                    return mc.level.enabledFeatures();
                case "dimensionType":
                    return mc.level.dimensionType();
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                case "toString":
                    return "architect's table design";
                default:
                    break;
            }
            if (m.isDefault()) {
                return InvocationHandler.invokeDefault(self, m, args);
            }
            Class<?> r = m.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class || r == long.class || r == short.class || r == byte.class) {
                return r == long.class ? 0L : r == short.class ? (short) 0 : r == byte.class ? (byte) 0 : 0;
            }
            if (r == float.class) {
                return 0f;
            }
            if (r == double.class) {
                return 0d;
            }
            return null;
        };
        return (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[]{LevelAccessor.class}, h);
    }
}
