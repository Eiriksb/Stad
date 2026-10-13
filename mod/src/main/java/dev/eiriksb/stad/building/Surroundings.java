package dev.eiriksb.stad.building;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashSet;
import java.util.Set;

/**
 * What's round a building that its workers need (PLAN.md §6.8): farmland within 16 blocks for a farmhouse, trees within
 * 24 for a lumber camp, a pond or river (eight blocks of water) within 6 for a fishery, a fenced pen beside a barn.
 * Server thread.
 */
public final class Surroundings {
    private Surroundings() {}

    public static final int FARMLAND = 16, TREES = 24, WATER = 6, PEN = 6;

    public static Set<String> of(Level level, BoundingBox box) {
        Set<String> out = new HashSet<>();
        int farmland = 0, water = 0, fences = 0, leaves = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int r = TREES;
        for (int x = box.minX() - r; x <= box.maxX() + r; x++) {
            for (int z = box.minZ() - r; z <= box.maxZ() + r; z++) {
                int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX())), dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
                int d = Math.max(dx, dz);
                if (!level.hasChunkAt(p.set(x, box.minY(), z))) {
                    continue;
                }
                for (int y = box.minY() - 6; y <= box.maxY() + 8; y++) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    if (s.isAir()) {
                        continue;
                    }
                    if (d <= FARMLAND && s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                        farmland++;
                    } else if (d <= WATER && s.getFluidState().is(FluidTags.WATER) && s.getFluidState().isSource()) {
                        water++;
                    } else if (d <= PEN && s.getBlock() instanceof FenceBlock) {
                        fences++;
                    } else if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT) && s.is(BlockTags.LEAVES)) {
                        leaves++;
                    }
                }
            }
        }
        if (farmland >= 4) {
            out.add("farmland");
        }
        if (water >= 8) {
            out.add("water");
        }
        if (fences >= 8) {
            out.add("pen");
        }
        if (leaves >= 20) {
            out.add("trees");
        }
        return out;
    }
}
