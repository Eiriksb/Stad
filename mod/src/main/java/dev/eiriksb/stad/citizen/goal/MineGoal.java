package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.Mine;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.Optional;

/** The miner digs the town's mine (a mine building from a pack) deeper, a layer at a time, and brings up what they dig. */
public class MineGoal extends WorkGoal {
    private BlockPos block;
    private boolean step;
    private boolean digging;

    public MineGoal(CitizenEntity citizen) {
        super(citizen, Jobs.MINER);
    }

    @Override
    protected ItemStack tool() {
        return new ItemStack(Items.IRON_PICKAXE);
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (block != null && (step ? level.getBlockState(block).canBeReplaced() : !level.getBlockState(block).isAir())) {
            return true;
        }
        BlockPos from = from(colony);
        Mine.Task task = Mine.shafts(colony).stream().sorted(Comparator.comparingDouble(s -> s.distSqr(from)))
                .map(s -> Mine.task(level, s, citizen.blockPosition())).flatMap(Optional::stream).findFirst().orElse(null);
        block = task == null ? null : task.pos();
        step = task != null && task.step();
        return block != null;
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (block == null) {
            return;
        }
        if (!approach.tick(citizen, block, 4.0, 0.6)) {
            digging = false;
            return;
        }
        citizen.getLookControl().setLookAt(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        if (step) { // a step where the shaft starts above the ground, from the mine's own stone
            level.setBlock(block, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
            level.playSound(null, block, net.minecraft.sounds.SoundEvents.STONE_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            block = null;
            return;
        }
        BlockState state = level.getBlockState(block);
        if (!digging) {
            digging = true; // harder stone takes longer
            cooldown = pace(Math.max(10, Math.min(40, (int) (state.getDestroySpeed(level, block) * 8))));
        }
        if (--cooldown > 0) {
            if (cooldown % 5 == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        digging = false;
        Mine.seal(level, block);
        state = level.getBlockState(block);
        if (!state.isAir() && state.getFluidState().isEmpty()) {
            for (ItemStack drop : Block.getDrops(state, level, block, level.getBlockEntity(block), citizen, tool())) {
                pocket(drop);
            }
        }
        level.levelEvent(2001, block, Block.getId(state)); // the breaking sound and bits
        level.removeBlock(block, false);
        citizen.didWork(1);
        block = null;
    }
}
