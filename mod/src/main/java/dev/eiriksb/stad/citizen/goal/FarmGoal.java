package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.WorkSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;

/** The farmer harvests ripe crops on the town's farmland, replants them, and sows empty farmland with their seeds. */
public class FarmGoal extends WorkGoal {
    private BlockPos task;

    public FarmGoal(CitizenEntity citizen) {
        super(citizen, Jobs.FARMER);
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return isSeed(stack) && stack.getCount() <= 16;
    }

    private static boolean isSeed(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (task != null && needsWork(level, task)) {
            return true;
        }
        task = nearWork(colony, WorkSites.farmland(level, colony).stream().filter(f -> needsWork(level, f)).toList(), 24).stream()
                .min(Comparator.comparingDouble(f -> f.distSqr(citizen.blockPosition()))).orElse(null);
        return task != null;
    }

    private boolean needsWork(ServerLevel level, BlockPos farmland) {
        BlockState above = level.getBlockState(farmland.above());
        if (above.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(above);
        }
        return above.isAir() && hasSeeds();
    }

    private boolean hasSeeds() {
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            if (isSeed(citizen.inventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null) {
            return;
        }
        BlockPos crop = task.above();
        if (!approach.tick(citizen, crop, 3.5, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(crop.getX() + 0.5, crop.getY(), crop.getZ() + 0.5);
        if (--cooldown > 0) {
            return;
        }
        cooldown = pace(12);
        BlockState state = level.getBlockState(crop);
        if (state.getBlock() instanceof CropBlock c && c.isMaxAge(state)) {
            for (ItemStack drop : Block.getDrops(state, level, crop, null)) {
                pocket(drop);
            }
            ItemStack seed = new ItemStack(state.getBlock().asItem());
            if (usePocketed(s -> s.is(seed.getItem()))) {
                level.setBlock(crop, c.getStateForAge(0), 3);
            } else {
                level.removeBlock(crop, false);
            }
            level.playSound(null, crop, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.8f, 1.0f);
            citizen.didWork(1);
        } else if (state.isAir()) {
            for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
                ItemStack s = citizen.inventory().getItem(i);
                if (isSeed(s)) {
                    level.setBlock(crop, ((BlockItem) s.getItem()).getBlock().defaultBlockState(), 3);
                    s.shrink(1);
                    level.playSound(null, crop, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8f, 1.0f);
                    break;
                }
            }
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        task = null;
        approach.reset();
    }
}
