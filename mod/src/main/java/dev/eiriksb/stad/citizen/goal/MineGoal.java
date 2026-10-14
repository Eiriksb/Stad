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

/**
 * The miner digs the town's mines and quarries deeper, a layer at a time, strip-mines a mine's shaft at the ore levels
 * ({@link Mine}), and brings up what they dig. Ore they come across, they dig out the whole vein of before going on.
 */
public class MineGoal extends WorkGoal {
    /** how far from where they found it a miner follows a vein (they reach it from the tunnel) */
    static final int VEIN = 3;

    /** An ore block to dig, and where the vein was first seen from. */
    record Vein(BlockPos pos, BlockPos from) {}

    private BlockPos block;
    private boolean step;
    private boolean digging;
    private final java.util.ArrayDeque<Vein> vein = new java.util.ArrayDeque<>();
    private BlockPos veinFrom;

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
        while (!vein.isEmpty()) { // the rest of a vein first
            Vein v = vein.poll();
            if (Mine.ore(level.getBlockState(v.pos())) && colony.contains(level.dimension(), v.pos())) {
                block = v.pos();
                veinFrom = v.from();
                step = false;
                return true;
            }
        }
        veinFrom = null;
        BlockPos from = from(colony);
        Mine.Task task = Mine.pits(colony).stream().sorted(Comparator.comparingDouble(s -> s.middle().distSqr(from)))
                .map(s -> Mine.task(level, colony, s, citizen.blockPosition())).flatMap(Optional::stream).findFirst().orElse(null);
        block = task == null ? null : task.pos();
        step = task != null && task.step();
        return block != null;
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        return Mine.pits(colony).isEmpty() ? "No mine to dig: build a Mine (from the building plans, or design one at the architect's table),"
                + " or lay out a Quarry" : "The mine is as deep as it goes: down to bedrock, or as deep as the quarry was laid out";
    }

    @Override
    protected String doingNow() {
        if (veinFrom != null) {
            return "Following a vein of ore";
        }
        if (block != null && step) {
            return "Building steps into the mine";
        }
        return block != null && Mine.level(block.getY()) || block != null && Mine.level(block.getY() - 1) ? "Strip-mining for ore" : "Digging the mine deeper";
    }

    /** Ore beside a block they've dug: the vein, as far as they can reach it from where they first saw it. */
    private void look(ServerLevel level, BlockPos dug) {
        BlockPos from = veinFrom != null ? veinFrom : dug;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = dug.relative(d);
            if (Mine.ore(level.getBlockState(n)) && n.distManhattan(from) <= VEIN * 2 && Math.abs(n.getX() - from.getX()) <= VEIN
                    && Math.abs(n.getY() - from.getY()) <= VEIN && Math.abs(n.getZ() - from.getZ()) <= VEIN
                    && vein.stream().noneMatch(v -> v.pos().equals(n)) && vein.size() < 64) {
                vein.add(new Vein(n, from));
            }
        }
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
            for (ItemStack drop : Block.getDrops(state, level, block, level.getBlockEntity(block), citizen, working())) {
                pocket(drop);
            }
        }
        level.levelEvent(2001, block, Block.getId(state)); // the breaking sound and bits
        if (state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES_DIAMOND)) {
            dev.eiriksb.stad.colony.Achievements.feat(colony, dev.eiriksb.stad.colony.Achievements.DIAMONDS);
        }
        if (block.getY() <= level.getMinBuildHeight() + 5) {
            dev.eiriksb.stad.colony.Achievements.feat(colony, dev.eiriksb.stad.colony.Achievements.BEDROCK);
        }
        level.removeBlock(block, false);
        look(level, block);
        worked(1);
        block = null;
    }
}
